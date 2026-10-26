package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.entity.Booking;
import in.yograj.learnspringboot.railway_booking.entity.JourneySeatInventory;
import in.yograj.learnspringboot.railway_booking.entity.SeatAllocation;
import in.yograj.learnspringboot.railway_booking.repository.BookingRepository;
import in.yograj.learnspringboot.railway_booking.repository.JourneySeatInventoryRepository;
import in.yograj.learnspringboot.railway_booking.repository.SeatAllocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SeatHoldReaperService {

    private static final Logger logger = LoggerFactory.getLogger(SeatHoldReaperService.class);

    public static final int MIN_BATCH_SIZE = 1;
    public static final int MAX_BATCH_SIZE = 500;
    public static final int DEFAULT_BATCH_SIZE = 50;

    private static final String STATUS_HOLD = "HOLD";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String STATUS_AVAILABLE = "AVAILABLE";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_PENDING_PAYMENT = "PENDING_PAYMENT";

    private final SeatAllocationRepository allocationRepository;
    private final BookingRepository bookingRepository;
    private final JourneySeatInventoryRepository inventoryRepository;
    private final TransactionTemplate requiresNewTemplate;
    private final Clock clock;
    private final boolean enabled;
    private final int configuredBatchSize;

    private final AtomicLong lastProcessedAllocationId = new AtomicLong(0L);
    private final AtomicLong lastProcessedLegacyInventoryId = new AtomicLong(0L);

    public SeatHoldReaperService(
            SeatAllocationRepository allocationRepository,
            BookingRepository bookingRepository,
            JourneySeatInventoryRepository inventoryRepository,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${railway.seat-hold-reaper.enabled:true}") boolean enabled,
            @Value("${railway.seat-hold-reaper.batch-size:50}") int batchSize) {
        this.allocationRepository = allocationRepository;
        this.bookingRepository = bookingRepository;
        this.inventoryRepository = inventoryRepository;
        this.clock = clock;
        this.enabled = enabled;
        this.configuredBatchSize = batchSize;

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTemplate = template;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getConfiguredBatchSize() {
        return configuredBatchSize;
    }

    public int clampBatchSize(int batchSize) {
        return Math.min(MAX_BATCH_SIZE, Math.max(MIN_BATCH_SIZE, batchSize));
    }

    public void resetCursors() {
        lastProcessedAllocationId.set(0L);
        lastProcessedLegacyInventoryId.set(0L);
    }

    public long getLastProcessedAllocationId() {
        return lastProcessedAllocationId.get();
    }

    @Scheduled(
            fixedDelayString = "${railway.seat-hold-reaper.fixed-delay-ms:30000}",
            initialDelayString = "${railway.seat-hold-reaper.initial-delay-ms:10000}"
    )
    public void scheduledReap() {
        if (!enabled) {
            logger.trace("Seat hold reaper scheduled task skipped because reaper is disabled via configuration");
            return;
        }
        try {
            reapExpiredHolds();
        } catch (Exception ex) {
            logger.error("Unexpected error in scheduled seat hold reaper execution: {}", ex.getMessage(), ex);
        }
    }

    public ReaperRunSummary reapExpiredHolds() {
        LocalDateTime cutoff = LocalDateTime.now(clock);
        return reapExpiredHolds(cutoff, this.configuredBatchSize);
    }

    public ReaperRunSummary reapExpiredHolds(LocalDateTime cutoff, int batchSize) {
        LocalDateTime startedAt = LocalDateTime.now(clock);
        long startNanos = System.nanoTime();
        int safeBatchSize = clampBatchSize(batchSize);

        logger.info("Seat hold reaper run initiated: cutoff={}, batchSize={}", cutoff, safeBatchSize);

        int totalCandidatesExamined = 0;
        int allocationsExpired = 0;
        int bookingsExpired = 0;
        int legacyHoldsNormalized = 0;
        int skippedCount = 0;
        int anomalyCount = 0;
        int failureCount = 0;

        // Process expired SeatAllocations using keyset pagination with cursor tracking
        Set<Long> attemptedAllocationIdsInRun = new HashSet<>();
        boolean wrappedAroundAllocations = false;

        while (totalCandidatesExamined < safeBatchSize) {
            int remaining = safeBatchSize - totalCandidatesExamined;
            Pageable pageable = PageRequest.of(0, remaining);
            long currentCursor = lastProcessedAllocationId.get();

            List<Long> candidateIds = allocationRepository.findExpiredHoldAllocationIdsAfter(cutoff, currentCursor, pageable);

            if (candidateIds.isEmpty()) {
                if (!wrappedAroundAllocations && currentCursor > 0L) {
                    wrappedAroundAllocations = true;
                    lastProcessedAllocationId.set(0L);
                    candidateIds = allocationRepository.findExpiredHoldAllocationIdsAfter(cutoff, 0L, pageable);
                }
                if (candidateIds.isEmpty()) {
                    break;
                }
            }

            List<Long> newCandidateIds = candidateIds.stream()
                    .filter(id -> !attemptedAllocationIdsInRun.contains(id))
                    .toList();

            if (newCandidateIds.isEmpty()) {
                break;
            }

            for (Long allocationId : newCandidateIds) {
                if (totalCandidatesExamined >= safeBatchSize) {
                    break;
                }

                attemptedAllocationIdsInRun.add(allocationId);
                totalCandidatesExamined++;
                lastProcessedAllocationId.set(allocationId);

                try {
                    ReapResult result = requiresNewTemplate.execute(status ->
                            reapSingleAllocation(allocationId, cutoff));
                    if (result == null) {
                        failureCount++;
                        continue;
                    }
                    switch (result) {
                        case EXPIRED_BOOKING_AND_ALLOCATION -> {
                            allocationsExpired++;
                            bookingsExpired++;
                        }
                        case EXPIRED_ALLOCATION -> allocationsExpired++;
                        case INTEGRITY_ANOMALY -> anomalyCount++;
                        case SKIPPED_CONFIRMED, SKIPPED_NOT_HOLD, SKIPPED_NOT_EXPIRED, SKIPPED_NOT_FOUND -> skippedCount++;
                        case FAILED -> failureCount++;
                        default -> {}
                    }
                } catch (Exception ex) {
                    logger.error("Error reaping seat allocation ID {}: {}", allocationId, ex.getMessage(), ex);
                    failureCount++;
                }
            }
        }

        // Process expired legacy JourneySeatInventory holds using keyset pagination
        Set<Long> attemptedInventoryIdsInRun = new HashSet<>();
        boolean wrappedAroundInventory = false;
        int legacyExamined = 0;

        while (legacyExamined < safeBatchSize) {
            int remaining = safeBatchSize - legacyExamined;
            Pageable pageable = PageRequest.of(0, remaining);
            long currentCursor = lastProcessedLegacyInventoryId.get();

            List<Long> inventoryIds = inventoryRepository.findExpiredLegacyHoldInventoryIdsAfter(cutoff, currentCursor, pageable);

            if (inventoryIds.isEmpty()) {
                if (!wrappedAroundInventory && currentCursor > 0L) {
                    wrappedAroundInventory = true;
                    lastProcessedLegacyInventoryId.set(0L);
                    inventoryIds = inventoryRepository.findExpiredLegacyHoldInventoryIdsAfter(cutoff, 0L, pageable);
                }
                if (inventoryIds.isEmpty()) {
                    break;
                }
            }

            List<Long> newInventoryIds = inventoryIds.stream()
                    .filter(id -> !attemptedInventoryIdsInRun.contains(id))
                    .toList();

            if (newInventoryIds.isEmpty()) {
                break;
            }

            for (Long inventoryId : newInventoryIds) {
                if (legacyExamined >= safeBatchSize) {
                    break;
                }

                attemptedInventoryIdsInRun.add(inventoryId);
                legacyExamined++;
                lastProcessedLegacyInventoryId.set(inventoryId);

                try {
                    ReapResult result = requiresNewTemplate.execute(status ->
                            reapSingleLegacyInventory(inventoryId, cutoff));
                    if (result == null) {
                        failureCount++;
                        continue;
                    }
                    switch (result) {
                        case LEGACY_HOLD_NORMALIZED -> legacyHoldsNormalized++;
                        case SKIPPED_NOT_HOLD, SKIPPED_NOT_EXPIRED, SKIPPED_NOT_FOUND -> skippedCount++;
                        case FAILED -> failureCount++;
                        default -> {}
                    }
                } catch (Exception ex) {
                    logger.error("Error reaping legacy inventory ID {}: {}", inventoryId, ex.getMessage(), ex);
                    failureCount++;
                }
            }
        }

        LocalDateTime endedAt = LocalDateTime.now(clock);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        ReaperRunSummary summary = new ReaperRunSummary(
                startedAt,
                endedAt,
                durationMs,
                totalCandidatesExamined + legacyExamined,
                allocationsExpired,
                bookingsExpired,
                legacyHoldsNormalized,
                skippedCount,
                anomalyCount,
                failureCount
        );

        logger.info("Seat hold reaper completed: durationMs={}, candidatesExamined={}, allocationsExpired={}, " +
                        "bookingsExpired={}, legacyHoldsNormalized={}, skipped={}, anomalies={}, failures={}",
                summary.durationMs(), summary.candidatesExamined(), summary.allocationsExpired(),
                summary.bookingsExpired(), summary.legacyHoldsNormalized(), summary.skippedCount(),
                summary.anomalyCount(), summary.failureCount());

        return summary;
    }

    public ReapResult reapSingleAllocation(Long allocationId, LocalDateTime cutoff) {
        // Enforce strict lock order to prevent deadlocks: Booking -> JourneySeatInventory -> SeatAllocation
        Optional<Booking> lockedBookingOpt = bookingRepository.findBySeatAllocationIdForUpdate(allocationId);

        if (lockedBookingOpt.isPresent()) {
            Booking booking = lockedBookingOpt.get();

            // Lock the parent JourneySeatInventory
            inventoryRepository.findByJourneyAndSeatForUpdate(
                    booking.getJourney().getJourneyId(), booking.getSeat().getSeatId());

            // Reload SeatAllocation under lock
            SeatAllocation allocation = allocationRepository.findById(allocationId).orElse(null);
            if (allocation == null) {
                return ReapResult.SKIPPED_NOT_FOUND;
            }

            // Inconsistent state guard: if linked booking is CONFIRMED, preserve state and log integrity anomaly
            if (STATUS_CONFIRMED.equalsIgnoreCase(booking.getBookingStatus())) {
                if (STATUS_HOLD.equalsIgnoreCase(allocation.getStatus())) {
                    logger.error("Data integrity anomaly detected: Booking ref {} (id: {}) is CONFIRMED but linked SeatAllocation id {} on Journey id {} (inventory id: {}) remains in HOLD status. Preserving state without modification for administrative review.",
                            booking.getBookingRef(), booking.getBookingId(), allocationId,
                            booking.getJourney() != null ? booking.getJourney().getJourneyId() : null,
                            allocation.getInventory() != null ? allocation.getInventory().getInventoryId() : null);
                    return ReapResult.INTEGRITY_ANOMALY;
                }
                return ReapResult.SKIPPED_CONFIRMED;
            }

            // Only release/expire allocations that are genuinely in HOLD
            if (!STATUS_HOLD.equalsIgnoreCase(allocation.getStatus())) {
                return ReapResult.SKIPPED_NOT_HOLD;
            }

            // Cutoff check: do not expire if hold is still active
            if (allocation.getHoldExpiresAt() != null && allocation.getHoldExpiresAt().isAfter(cutoff)) {
                return ReapResult.SKIPPED_NOT_EXPIRED;
            }

            LocalDateTime now = LocalDateTime.now(clock);

            if (STATUS_PENDING_PAYMENT.equalsIgnoreCase(booking.getBookingStatus())) {
                booking.setBookingStatus(STATUS_EXPIRED);
                booking.setPaymentStatus(STATUS_EXPIRED);
                bookingRepository.save(booking);

                allocation.setStatus(STATUS_EXPIRED);
                allocation.setUpdatedAt(now);
                allocationRepository.save(allocation);

                logger.info("Reaped expired hold: SeatAllocation ID {} and linked Booking {} (ref: {}) transitioned to EXPIRED",
                        allocationId, booking.getBookingId(), booking.getBookingRef());
                return ReapResult.EXPIRED_BOOKING_AND_ALLOCATION;
            } else {
                allocation.setStatus(STATUS_EXPIRED);
                allocation.setUpdatedAt(now);
                allocationRepository.save(allocation);

                logger.info("Reaped expired hold: SeatAllocation ID {} transitioned to EXPIRED (linked Booking {} in status {})",
                        allocationId, booking.getBookingId(), booking.getBookingStatus());
                return ReapResult.EXPIRED_ALLOCATION;
            }
        } else {
            // Abandoned hold (no linked booking)
            SeatAllocation allocation = allocationRepository.findById(allocationId).orElse(null);
            if (allocation == null) {
                return ReapResult.SKIPPED_NOT_FOUND;
            }

            // Lock parent JourneySeatInventory to serialize against concurrent holds or booking attempts
            inventoryRepository.findByJourneyAndSeatForUpdate(
                    allocation.getInventory().getJourney().getJourneyId(),
                    allocation.getInventory().getSeat().getSeatId());

            // Reload allocation under lock
            allocation = allocationRepository.findById(allocationId).orElse(null);
            if (allocation == null) {
                return ReapResult.SKIPPED_NOT_FOUND;
            }

            // Double check if a booking was created just before the inventory lock was obtained
            Optional<Booking> lateBookingOpt = bookingRepository.findBySeatAllocation_SeatAllocationId(allocationId);
            if (lateBookingOpt.isPresent()) {
                Booking booking = lateBookingOpt.get();
                if (STATUS_CONFIRMED.equalsIgnoreCase(booking.getBookingStatus())) {
                    if (STATUS_HOLD.equalsIgnoreCase(allocation.getStatus())) {
                        logger.error("Data integrity anomaly detected: Booking ref {} (id: {}) is CONFIRMED but linked SeatAllocation id {} on Journey id {} (inventory id: {}) remains in HOLD status. Preserving state without modification for administrative review.",
                                booking.getBookingRef(), booking.getBookingId(), allocationId,
                                booking.getJourney() != null ? booking.getJourney().getJourneyId() : null,
                                allocation.getInventory() != null ? allocation.getInventory().getInventoryId() : null);
                        return ReapResult.INTEGRITY_ANOMALY;
                    }
                    return ReapResult.SKIPPED_CONFIRMED;
                }
                if (STATUS_PENDING_PAYMENT.equalsIgnoreCase(booking.getBookingStatus())) {
                    LocalDateTime now = LocalDateTime.now(clock);
                    booking.setBookingStatus(STATUS_EXPIRED);
                    booking.setPaymentStatus(STATUS_EXPIRED);
                    bookingRepository.save(booking);

                    allocation.setStatus(STATUS_EXPIRED);
                    allocation.setUpdatedAt(now);
                    allocationRepository.save(allocation);
                    return ReapResult.EXPIRED_BOOKING_AND_ALLOCATION;
                }
            }

            if (!STATUS_HOLD.equalsIgnoreCase(allocation.getStatus())) {
                return ReapResult.SKIPPED_NOT_HOLD;
            }

            if (allocation.getHoldExpiresAt() != null && allocation.getHoldExpiresAt().isAfter(cutoff)) {
                return ReapResult.SKIPPED_NOT_EXPIRED;
            }

            LocalDateTime now = LocalDateTime.now(clock);
            allocation.setStatus(STATUS_EXPIRED);
            allocation.setUpdatedAt(now);
            allocationRepository.save(allocation);

            logger.info("Reaped abandoned expired hold: SeatAllocation ID {} transitioned to EXPIRED", allocationId);
            return ReapResult.EXPIRED_ALLOCATION;
        }
    }

    public ReapResult reapSingleLegacyInventory(Long inventoryId, LocalDateTime cutoff) {
        JourneySeatInventory inventory = inventoryRepository.findById(inventoryId).orElse(null);
        if (inventory == null) {
            return ReapResult.SKIPPED_NOT_FOUND;
        }

        inventory = inventoryRepository.findByJourneyAndSeatForUpdate(
                inventory.getJourney().getJourneyId(), inventory.getSeat().getSeatId())
                .orElse(null);
        if (inventory == null) {
            return ReapResult.SKIPPED_NOT_FOUND;
        }

        if (!STATUS_HOLD.equalsIgnoreCase(inventory.getStatus())) {
            return ReapResult.SKIPPED_NOT_HOLD;
        }

        if (inventory.getHoldExpiresAt() != null && inventory.getHoldExpiresAt().isAfter(cutoff)) {
            return ReapResult.SKIPPED_NOT_EXPIRED;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        inventory.setStatus(STATUS_AVAILABLE);
        inventory.setHoldExpiresAt(null);
        inventory.setHoldOwner(null);
        inventory.setUpdatedAt(now);
        inventoryRepository.save(inventory);

        logger.info("Reaped legacy inventory-level hold for JourneySeatInventory ID {}", inventoryId);
        return ReapResult.LEGACY_HOLD_NORMALIZED;
    }
}
