package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.dto.BookingDetailResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.BookingMode;
import in.yograj.learnspringboot.railway_booking.dto.BookingResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.CreateBookingRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PageResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentResponseDTO;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.exception.SeatHoldExpiredException;
import in.yograj.learnspringboot.railway_booking.outbox.OutboxEventService;
import in.yograj.learnspringboot.railway_booking.outbox.event.BookingCancelledEvent;
import in.yograj.learnspringboot.railway_booking.outbox.event.BookingConfirmedEvent;
import in.yograj.learnspringboot.railway_booking.outbox.event.PaymentFailedEvent;
import in.yograj.learnspringboot.railway_booking.repository.BookingRepository;
import in.yograj.learnspringboot.railway_booking.repository.JourneySeatInventoryRepository;
import in.yograj.learnspringboot.railway_booking.repository.PaymentTransactionRepository;
import in.yograj.learnspringboot.railway_booking.repository.SeatAllocationRepository;
import in.yograj.learnspringboot.railway_booking.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class BookingService {

    private static final String HOLD = "HOLD";
    private static final String BOOKED = "BOOKED";
    private static final String EXPIRED = "EXPIRED";
    private static final String PENDING_PAYMENT = "PENDING_PAYMENT";
    private static final String CONFIRMED = "CONFIRMED";
    private static final String CANCELLED = "CANCELLED";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_REFUNDED = "REFUNDED";

    public static final BigDecimal DEFAULT_MOCK_FARE = new BigDecimal("500.00");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final BookingRepository bookingRepository;
    private final JourneySeatInventoryRepository inventoryRepository;
    private final SeatAllocationRepository allocationRepository;
    private final SeatInventoryService seatInventoryService;
    private final UserRepository userRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final TatkalJourneyPolicy tatkalJourneyPolicy;
    private final TatkalAdmissionPassService tatkalAdmissionPassService;
    private final OutboxEventService outboxEventService;

    public BookingService(BookingRepository bookingRepository,
                          JourneySeatInventoryRepository inventoryRepository,
                          SeatAllocationRepository allocationRepository,
                          SeatInventoryService seatInventoryService,
                          UserRepository userRepository,
                          PaymentTransactionRepository paymentTransactionRepository,
                          TatkalJourneyPolicy tatkalJourneyPolicy,
                          TatkalAdmissionPassService tatkalAdmissionPassService) {
        this(bookingRepository, inventoryRepository, allocationRepository,
                seatInventoryService, userRepository, paymentTransactionRepository,
                tatkalJourneyPolicy, tatkalAdmissionPassService, null);
    }

    @Autowired
    public BookingService(BookingRepository bookingRepository,
                          JourneySeatInventoryRepository inventoryRepository,
                          SeatAllocationRepository allocationRepository,
                          SeatInventoryService seatInventoryService,
                          UserRepository userRepository,
                          PaymentTransactionRepository paymentTransactionRepository,
                          TatkalJourneyPolicy tatkalJourneyPolicy,
                          TatkalAdmissionPassService tatkalAdmissionPassService,
                          OutboxEventService outboxEventService) {
        this.bookingRepository = bookingRepository;
        this.inventoryRepository = inventoryRepository;
        this.allocationRepository = allocationRepository;
        this.seatInventoryService = seatInventoryService;
        this.userRepository = userRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.tatkalJourneyPolicy = tatkalJourneyPolicy;
        this.tatkalAdmissionPassService = tatkalAdmissionPassService;
        this.outboxEventService = outboxEventService;
    }

    @Transactional
    public BookingResponseDTO createBooking(CreateBookingRequestDTO request, String username) {
        return createBooking(request, username, null);
    }

    @Transactional
    public BookingResponseDTO createBooking(CreateBookingRequestDTO request, String username,
                                             String admissionPass) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));

        // Parent run-seat row lock same seat ke holds aur booking decisions ko serialize karta hai.
        JourneySeatInventory inventory = inventoryRepository
                .findByJourneyAndSeatForUpdate(request.journeyId(), request.seatId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Seat inventory not found for journeyId=" + request.journeyId()
                                + ", seatId=" + request.seatId()));
        seatInventoryService.normalizeLegacyHold(inventory);
        SeatInventoryService.JourneySegment segment = seatInventoryService.resolveSegment(
                request.journeyId(), request.boardingJourneyStopId(), request.alightingJourneyStopId());

        LocalDateTime now = LocalDateTime.now();
        SeatAllocation allocation = allocationRepository
                .findByInventory_InventoryId(inventory.getInventoryId()).stream()
                .filter(candidate -> HOLD.equalsIgnoreCase(candidate.getStatus()))
                .filter(candidate -> candidate.getHoldExpiresAt() != null
                        && candidate.getHoldExpiresAt().isAfter(now))
                .filter(candidate -> candidate.getBoardingStop().getJourneyStopId()
                        .equals(segment.boardingStop().getJourneyStopId()))
                .filter(candidate -> candidate.getAlightingStop().getJourneyStopId()
                        .equals(segment.alightingStop().getJourneyStopId()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "An active hold for this exact travel segment is required"));

        // Main hold owner verify karta hu, taaki dusre user ka segment hold booking me na badle.
        if (!allocation.getHoldOwner().getUserId().equals(user.getUserId())) {
            throw new AccessDeniedException("Seat hold belongs to another user");
        }
        if (allocation.getBookingMode() != request.bookingMode()) {
            throw new BadRequestException("Booking mode must match the mode used to acquire the seat hold");
        }
        boolean tatkalJourney = tatkalJourneyPolicy.isTatkalJourney(request.journeyId());
        if (tatkalJourney != (allocation.getBookingMode() == BookingMode.TATKAL)) {
            throw new BadRequestException("Seat hold booking mode is not valid for this journey");
        }
        if (allocation.getBookingMode() == BookingMode.TATKAL
                && (allocation.getAdmissionEntryId() == null || allocation.getAdmissionEntryId().isBlank())) {
            throw new BadRequestException("Tatkal booking requires a seat hold created with an admitted queue pass");
        }
        if (allocation.getBookingMode() == BookingMode.TATKAL && admissionPass != null && !admissionPass.isBlank()) {
            TatkalAdmissionPassService.AdmissionClaims claims = tatkalAdmissionPassService
                    .verifyForExistingHold(admissionPass, username, request.journeyId());
            if (!claims.queueEntryId().equals(allocation.getAdmissionEntryId())) {
                throw new in.yograj.learnspringboot.railway_booking.exception.InvalidAdmissionPassException(
                        "Tatkal pass does not match the active seat hold");
            }
        }

        if (bookingRepository.existsBySeatAllocation_SeatAllocationIdAndBookingStatus(
                allocation.getSeatAllocationId(), PENDING_PAYMENT)) {
            throw new BadRequestException("A pending booking already exists for this seat hold");
        }

        Booking booking = new Booking();
        booking.setUser(user);
        booking.setJourney(inventory.getJourney());
        booking.setCoach(inventory.getCoach());
        booking.setSeat(inventory.getSeat());
        booking.setInventory(inventory);
        booking.setSeatAllocation(allocation);
        booking.setBookingRef("RB-" + UUID.randomUUID().toString().replace("-", ""));
        booking.setPnr(generateUniquePnr());
        booking.setBookingStatus(PENDING_PAYMENT);
        booking.setPaymentStatus("PENDING");
        // Fare abhi model nahi hua, isliye amount client se nahi leta aur fake fare bhi set nahi karta.
        booking.setAmount(null);
        booking.setCreatedAt(now);

        Booking savedBooking = bookingRepository.save(booking);
        // Payment/confirmation tak allocation HOLD hi rehti hai; yahan BOOKED transition nahi hota.
        return toResponse(savedBooking);
    }

    @Transactional(noRollbackFor = SeatHoldExpiredException.class)
    public PaymentResponseDTO processPayment(String bookingRef, PaymentRequestDTO request, String username) {
        if (bookingRef == null || bookingRef.isBlank()) {
            throw new BadRequestException("Booking reference is required");
        }

        PaymentRequestDTO safeRequest = request != null
                ? request
                : new PaymentRequestDTO("MOCK_UPI", null, false);

        // 1. Lock the booking row pessimistically to serialize concurrent payments on this booking
        Booking booking = bookingRepository.findByBookingRefForUpdate(bookingRef)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found for reference: " + bookingRef));

        // 2. Ownership verification
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        if (!isAdmin(user) && !booking.getUser().getUserId().equals(user.getUserId())) {
            throw new AccessDeniedException("You are not authorized to pay for this booking");
        }

        // 3. Acquire pessimistic lock on the parent JourneySeatInventory
        // Coordinates with SeatInventoryService.releaseSeat and holdSeat to prevent race conditions
        inventoryRepository.findByJourneyAndSeatForUpdate(
                booking.getJourney().getJourneyId(), booking.getSeat().getSeatId())
                .orElseThrow(() -> new ResourceNotFoundException("Seat inventory not found"));

        // 4. Idempotency check: same key + same parameters returns cached result; same key + different parameters throws 409
        if (safeRequest.idempotencyKey() != null && !safeRequest.idempotencyKey().isBlank()) {
            String trimmedKey = safeRequest.idempotencyKey().trim();
            Optional<PaymentTransaction> existingTxn = paymentTransactionRepository
                    .findByBookingRefAndIdempotencyKey(bookingRef, trimmedKey);
            if (existingTxn.isPresent()) {
                PaymentTransaction txn = existingTxn.get();

                boolean methodMatches = safeRequest.paymentMethod().equalsIgnoreCase(txn.getPaymentMethod());
                boolean simulateFailureMatches = Boolean.TRUE.equals(safeRequest.simulateFailure())
                        == Boolean.TRUE.equals(txn.getSimulateFailure());

                if (!methodMatches || !simulateFailureMatches) {
                    throw new IllegalStateException("Idempotency key '" + trimmedKey
                            + "' was previously used with different request parameters");
                }

                return new PaymentResponseDTO(
                        txn.getTransactionRef(),
                        booking.getBookingRef(),
                        booking.getBookingId(),
                        txn.getAmount(),
                        txn.getStatus(),
                        booking.getBookingStatus(),
                        txn.getPaymentMethod(),
                        "Idempotent response for transaction " + txn.getTransactionRef(),
                        txn.getCreatedAt()
                );
            }
        }

        // 5. Duplicate payment check: booking is already confirmed
        if (CONFIRMED.equalsIgnoreCase(booking.getBookingStatus())) {
            throw new IllegalStateException("Booking has already been confirmed");
        }

        if ("CANCELLED".equalsIgnoreCase(booking.getBookingStatus())
                || EXPIRED.equalsIgnoreCase(booking.getBookingStatus())) {
            throw new IllegalStateException("Booking cannot be paid in status: " + booking.getBookingStatus());
        }

        // 6. Reload SeatAllocation to observe any committed state from concurrent operations (e.g., releaseSeat)
        if (booking.getSeatAllocation() == null) {
            throw new IllegalStateException("Booking does not have an associated seat allocation");
        }
        SeatAllocation allocation = allocationRepository.findById(
                booking.getSeatAllocation().getSeatAllocationId()
        ).orElseThrow(() -> new IllegalStateException("Associated seat allocation not found"));

        LocalDateTime now = LocalDateTime.now();
        String txnRef = "TXN-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String idempotencyKey = safeRequest.idempotencyKey() != null && !safeRequest.idempotencyKey().isBlank()
                ? safeRequest.idempotencyKey().trim()
                : null;

        BigDecimal amountToCharge = booking.getAmount() != null
                ? booking.getAmount()
                : DEFAULT_MOCK_FARE;
        booking.setAmount(amountToCharge);

        // 7. Check hold expiration
        if (allocation.getHoldExpiresAt() != null && !allocation.getHoldExpiresAt().isAfter(now)) {
            allocation.setStatus(EXPIRED);
            allocation.setUpdatedAt(now);
            allocationRepository.save(allocation);

            booking.setBookingStatus(EXPIRED);
            booking.setPaymentStatus(EXPIRED);
            bookingRepository.save(booking);

            PaymentTransaction expiredTxn = new PaymentTransaction();
            expiredTxn.setBooking(booking);
            expiredTxn.setBookingRef(booking.getBookingRef());
            expiredTxn.setTransactionRef(txnRef);
            expiredTxn.setPaymentMethod(safeRequest.paymentMethod());
            expiredTxn.setAmount(amountToCharge);
            expiredTxn.setStatus(STATUS_FAILED);
            expiredTxn.setFailureReason("Seat hold has expired");
            expiredTxn.setIdempotencyKey(idempotencyKey);
            expiredTxn.setSimulateFailure(safeRequest.simulateFailure());
            expiredTxn.setCreatedAt(now);
            paymentTransactionRepository.save(expiredTxn);

            throw new SeatHoldExpiredException("Seat hold has expired. Booking can no longer be confirmed.");
        }

        if (!HOLD.equalsIgnoreCase(allocation.getStatus())) {
            throw new IllegalStateException("Seat allocation is not on hold (current status: " + allocation.getStatus() + ")");
        }

        // 8. Process payment (mock payment flow)
        if (Boolean.TRUE.equals(safeRequest.simulateFailure())) {
            PaymentTransaction failedTxn = new PaymentTransaction();
            failedTxn.setBooking(booking);
            failedTxn.setBookingRef(booking.getBookingRef());
            failedTxn.setTransactionRef(txnRef);
            failedTxn.setPaymentMethod(safeRequest.paymentMethod());
            failedTxn.setAmount(amountToCharge);
            failedTxn.setStatus(STATUS_FAILED);
            failedTxn.setFailureReason("Simulated payment failure");
            failedTxn.setIdempotencyKey(idempotencyKey);
            failedTxn.setSimulateFailure(safeRequest.simulateFailure());
            failedTxn.setCreatedAt(now);
            paymentTransactionRepository.save(failedTxn);

            booking.setPaymentStatus(STATUS_FAILED);
            bookingRepository.save(booking);

            if (outboxEventService != null) {
                outboxEventService.stageEvent(
                        "PAYMENT",
                        failedTxn.getTransactionRef(),
                        "PAYMENT_FAILED",
                        new PaymentFailedEvent(
                                booking.getBookingRef(),
                                failedTxn.getTransactionRef(),
                                amountToCharge,
                                safeRequest.paymentMethod(),
                                failedTxn.getFailureReason(),
                                now
                        )
                );
            }

            return new PaymentResponseDTO(
                    txnRef,
                    booking.getBookingRef(),
                    booking.getBookingId(),
                    amountToCharge,
                    STATUS_FAILED,
                    booking.getBookingStatus(), // remains PENDING_PAYMENT (retryable)
                    safeRequest.paymentMethod(),
                    "Payment failed: Simulated payment failure",
                    now
            );
        }

        // Payment Success
        PaymentTransaction successTxn = new PaymentTransaction();
        successTxn.setBooking(booking);
        successTxn.setBookingRef(booking.getBookingRef());
        successTxn.setTransactionRef(txnRef);
        successTxn.setPaymentMethod(safeRequest.paymentMethod());
        successTxn.setAmount(amountToCharge);
        successTxn.setStatus(STATUS_SUCCESS);
        successTxn.setFailureReason(null);
        successTxn.setIdempotencyKey(idempotencyKey);
        successTxn.setSimulateFailure(safeRequest.simulateFailure());
        successTxn.setCreatedAt(now);
        paymentTransactionRepository.save(successTxn);

        // State Transitions: Booking -> CONFIRMED, Allocation -> BOOKED
        booking.setBookingStatus(CONFIRMED);
        booking.setPaymentStatus(STATUS_SUCCESS);
        bookingRepository.save(booking);

        allocation.setStatus(BOOKED);
        allocation.setUpdatedAt(now);
        allocationRepository.save(allocation);

        if (outboxEventService != null) {
            outboxEventService.stageEvent(
                    "BOOKING",
                    booking.getBookingRef(),
                    "BOOKING_CONFIRMED",
                    new BookingConfirmedEvent(
                            booking.getBookingRef(),
                            booking.getPnr() != null ? booking.getPnr() : "",
                            booking.getBookingId(),
                            booking.getUser().getUserId(),
                            booking.getUser().getUsername(),
                            booking.getJourney().getJourneyId(),
                            amountToCharge,
                            safeRequest.paymentMethod(),
                            successTxn.getTransactionRef(),
                            CONFIRMED,
                            now
                    )
            );
        }

        return new PaymentResponseDTO(
                txnRef,
                booking.getBookingRef(),
                booking.getBookingId(),
                amountToCharge,
                STATUS_SUCCESS,
                CONFIRMED,
                safeRequest.paymentMethod(),
                "Payment successful and booking confirmed",
                now
        );
    }

    @Transactional
    public BookingDetailResponseDTO cancelBooking(String bookingRef, String username) {
        if (bookingRef == null || bookingRef.isBlank()) {
            throw new BadRequestException("Booking reference is required");
        }

        // 1. Pessimistic lock on booking to serialize concurrent cancellations or payments
        Booking booking = bookingRepository.findByBookingRefForUpdate(bookingRef.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found for reference: " + bookingRef));

        // 2. Ownership verification: user can cancel only their own booking; admin follows authorization rules
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        if (!isAdmin(user) && !booking.getUser().getUserId().equals(user.getUserId())) {
            throw new AccessDeniedException("You are not authorized to cancel this booking");
        }

        // 3. Status eligibility checks
        if (CANCELLED.equalsIgnoreCase(booking.getBookingStatus())) {
            throw new IllegalStateException("Booking has already been cancelled");
        }
        if (EXPIRED.equalsIgnoreCase(booking.getBookingStatus())) {
            throw new IllegalStateException("Booking cannot be cancelled in status: " + booking.getBookingStatus());
        }
        if (!CONFIRMED.equalsIgnoreCase(booking.getBookingStatus())
                && !PENDING_PAYMENT.equalsIgnoreCase(booking.getBookingStatus())) {
            throw new IllegalStateException("Booking cannot be cancelled in status: " + booking.getBookingStatus());
        }

        // 4. Verify payment refund preconditions if booking was paid
        PaymentTransaction originalPaymentTxn = null;
        BigDecimal verifiedRefundAmount = null;
        if (STATUS_SUCCESS.equalsIgnoreCase(booking.getPaymentStatus())) {
            List<PaymentTransaction> txns = paymentTransactionRepository.findByBookingRef(booking.getBookingRef());
            List<PaymentTransaction> successfulTxns = txns.stream()
                    .filter(t -> STATUS_SUCCESS.equalsIgnoreCase(t.getStatus()))
                    .toList();

            if (successfulTxns.isEmpty()) {
                throw new IllegalStateException(
                        "Cannot process refund: original successful payment transaction not found for booking " + booking.getBookingRef()
                );
            }
            if (successfulTxns.size() > 1) {
                throw new IllegalStateException(
                        "Cannot process refund: multiple successful payment transactions found for booking " + booking.getBookingRef()
                );
            }

            originalPaymentTxn = successfulTxns.get(0);
            BigDecimal paidAmount = originalPaymentTxn.getAmount();
            if (paidAmount == null || paidAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException(
                        "Cannot process refund: original payment transaction has non-positive or null amount for booking " + booking.getBookingRef()
                );
            }

            if (booking.getAmount() != null && booking.getAmount().compareTo(paidAmount) != 0) {
                throw new IllegalStateException(
                        "Cannot process refund: booking amount (" + booking.getAmount()
                                + ") does not match original payment transaction amount (" + paidAmount
                                + ") for booking " + booking.getBookingRef()
                );
            }

            verifiedRefundAmount = paidAmount;
            if (booking.getAmount() == null) {
                booking.setAmount(verifiedRefundAmount);
            }
        }

        LocalDateTime now = LocalDateTime.now();

        // 5. Lock parent inventory and normalize legacy hold or blocked state if present
        if (booking.getJourney() != null && booking.getSeat() != null) {
            inventoryRepository.findByJourneyAndSeatForUpdate(
                    booking.getJourney().getJourneyId(), booking.getSeat().getSeatId())
                    .ifPresent(inventory -> {
                        if (HOLD.equalsIgnoreCase(inventory.getStatus())) {
                            inventory.setStatus("AVAILABLE");
                            inventory.setHoldExpiresAt(null);
                            inventory.setHoldOwner(null);
                            inventory.setUpdatedAt(now);
                            inventoryRepository.save(inventory);
                        } else if ("BLOCKED".equalsIgnoreCase(inventory.getStatus())) {
                            inventory.setStatus("AVAILABLE");
                            inventory.setUpdatedAt(now);
                            inventoryRepository.save(inventory);
                        }
                    });
        }

        // 6. Release seat allocation: set status to CANCELLED so GiST constraint and isBlocking treat seat as available
        SeatAllocation allocation = booking.getSeatAllocation();
        if (allocation != null) {
            allocation.setStatus(CANCELLED);
            allocation.setUpdatedAt(now);
            allocationRepository.save(allocation);
        }

        // 7. Update booking status and process mock refund ledger record if eligible
        booking.setBookingStatus(CANCELLED);
        if ("PENDING".equalsIgnoreCase(booking.getPaymentStatus())) {
            booking.setPaymentStatus(CANCELLED);
        } else if (STATUS_SUCCESS.equalsIgnoreCase(booking.getPaymentStatus())) {
            // Full mock refund: in absence of a documented fee policy, the verified paid amount is refunded in the mock ledger.
            booking.setPaymentStatus(STATUS_REFUNDED);

            // Record the mock refund transaction idempotently
            String refundIdempotencyKey = "REFUND-" + booking.getBookingRef();
            Optional<PaymentTransaction> existingRefund = paymentTransactionRepository
                    .findByBookingRefAndIdempotencyKey(booking.getBookingRef(), refundIdempotencyKey);

            if (existingRefund.isEmpty()) {
                String paymentMethod = originalPaymentTxn.getPaymentMethod() != null
                        && !originalPaymentTxn.getPaymentMethod().isBlank()
                        ? originalPaymentTxn.getPaymentMethod()
                        : "MOCK_UPI";

                PaymentTransaction refundTxn = new PaymentTransaction();
                refundTxn.setBooking(booking);
                refundTxn.setBookingRef(booking.getBookingRef());
                refundTxn.setTransactionRef("TXN-REFUND-" + UUID.randomUUID().toString().replace("-", "").toUpperCase());
                refundTxn.setPaymentMethod(paymentMethod);
                refundTxn.setAmount(verifiedRefundAmount);
                refundTxn.setStatus(STATUS_REFUNDED);
                refundTxn.setFailureReason(null);
                refundTxn.setIdempotencyKey(refundIdempotencyKey);
                refundTxn.setSimulateFailure(false);
                refundTxn.setCreatedAt(now);
                paymentTransactionRepository.save(refundTxn);
            }
        }
        bookingRepository.save(booking);

        if (outboxEventService != null) {
            outboxEventService.stageEvent(
                    "BOOKING",
                    booking.getBookingRef(),
                    "BOOKING_CANCELLED",
                    new BookingCancelledEvent(
                            booking.getBookingRef(),
                            booking.getPnr() != null ? booking.getPnr() : "",
                            booking.getBookingId(),
                            booking.getUser().getUserId(),
                            booking.getUser().getUsername(),
                            CANCELLED,
                            booking.getPaymentStatus(),
                            verifiedRefundAmount != null ? verifiedRefundAmount : BigDecimal.ZERO,
                            now
                    )
            );
        }

        return toDetailResponse(booking);
    }

    @Transactional(readOnly = true)
    public BookingDetailResponseDTO getBookingByRef(String bookingRef, String username) {
        if (bookingRef == null || bookingRef.isBlank()) {
            throw new BadRequestException("Booking reference is required");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));

        Booking booking = bookingRepository.findByBookingRef(bookingRef.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found for reference: " + bookingRef));

        if (!isAdmin(user) && !booking.getUser().getUserId().equals(user.getUserId())) {
            throw new AccessDeniedException("You are not authorized to view this booking");
        }

        return toDetailResponse(booking);
    }

    @Transactional(readOnly = true)
    public BookingDetailResponseDTO getBookingByPnr(String pnr, String username) {
        if (pnr == null || pnr.isBlank()) {
            throw new BadRequestException("PNR is required");
        }
        String trimmedPnr = pnr.trim();
        if (!trimmedPnr.matches("^\\d{10}$")) {
            throw new BadRequestException("PNR must be exactly 10 numeric digits");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));

        Booking booking = bookingRepository.findByPnr(trimmedPnr)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found for PNR: " + trimmedPnr));

        if (!isAdmin(user) && !booking.getUser().getUserId().equals(user.getUserId())) {
            // Prevent enumeration leaks: return consistent not-found response without revealing another user's PNR exists
            throw new ResourceNotFoundException("Booking not found for PNR: " + trimmedPnr);
        }

        return toDetailResponse(booking);
    }

    @Transactional(readOnly = true)
    public PageResponseDTO<BookingDetailResponseDTO> getBookingHistory(int page, int size, String username) {
        if (page < 0) {
            throw new BadRequestException("Page index must not be less than zero");
        }
        if (size < 1) {
            throw new BadRequestException("Page size must be greater than zero");
        }
        if (size > 100) {
            throw new BadRequestException("Page size must not exceed 100");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));

        Pageable pageable = PageRequest.of(
                page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "bookingId")));

        Page<Booking> pageResult = isAdmin(user)
                ? bookingRepository.findAll(pageable)
                : bookingRepository.findByUser_UserId(user.getUserId(), pageable);

        return PageResponseDTO.of(pageResult.map(this::toDetailResponse));
    }

    public BookingDetailResponseDTO toDetailResponse(Booking booking) {
        Journey journey = booking.getJourney();
        Train train = journey != null ? journey.getTrain() : null;
        Station fromStation = journey != null ? journey.getFromStation() : null;
        Station toStation = journey != null ? journey.getToStation() : null;
        Coach coach = booking.getCoach();
        Seat seat = booking.getSeat();
        SeatAllocation allocation = booking.getSeatAllocation();
        User bookingUser = booking.getUser();

        return new BookingDetailResponseDTO(
                booking.getBookingId(),
                booking.getBookingRef(),
                booking.getPnr(),
                bookingUser != null ? bookingUser.getUserId() : null,
                bookingUser != null ? bookingUser.getUsername() : null,
                journey != null ? journey.getJourneyId() : null,
                train != null ? train.getTrainNumber() : null,
                train != null ? train.getTrainName() : null,
                fromStation != null ? fromStation.getStationCode() : null,
                fromStation != null ? fromStation.getStationName() : null,
                toStation != null ? toStation.getStationCode() : null,
                toStation != null ? toStation.getStationName() : null,
                journey != null ? journey.getJourneyDate() : null,
                journey != null ? journey.getDepartureTime() : null,
                journey != null ? journey.getArrivalTime() : null,
                coach != null ? coach.getCoachId() : null,
                coach != null ? coach.getCoachNumber() : null,
                coach != null ? coach.getCoachType() : null,
                seat != null ? seat.getSeatId() : null,
                seat != null ? seat.getSeatNumber() : null,
                seat != null ? seat.getSeatType() : null,
                allocation != null ? allocation.getSeatAllocationId() : null,
                booking.getBookingStatus(),
                booking.getPaymentStatus(),
                booking.getAmount(),
                booking.getCreatedAt()
        );
    }

    private BookingResponseDTO toResponse(Booking booking) {
        return new BookingResponseDTO(
                booking.getBookingId(),
                booking.getBookingRef(),
                booking.getPnr(),
                booking.getJourney().getJourneyId(),
                booking.getSeat().getSeatId(),
                booking.getCoach().getCoachNumber(),
                booking.getSeat().getSeatNumber(),
                booking.getBookingStatus(),
                booking.getPaymentStatus(),
                booking.getAmount(),
                booking.getCreatedAt()
        );
    }

    private String generateUniquePnr() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(10);
            for (int i = 0; i < 10; i++) {
                sb.append(SECURE_RANDOM.nextInt(10));
            }
            String candidate = sb.toString();
            if (!bookingRepository.existsByPnr(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Failed to generate a unique 10-digit PNR after 10 attempts");
    }

    private boolean isAdmin(User user) {
        if (user == null || user.getRole() == null) {
            return false;
        }
        String role = user.getRole().trim();
        return "ROLE_ADMIN".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role);
    }
}
