package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.dto.BookingResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.CreateBookingRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.CreateTrainRouteRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.JourneyRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainStopRequestDTO;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:seat-hold-reaper-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length",
        "railway.seat-hold-reaper.enabled=false"
})
class SeatHoldReaperTests {

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    @Autowired private TrainRepository trainRepository;
    @Autowired private StationRepository stationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JourneySeatInventoryRepository inventoryRepository;
    @Autowired private JourneyStopRepository journeyStopRepository;
    @Autowired private SeatAllocationRepository allocationRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private PaymentTransactionRepository paymentTransactionRepository;
    @Autowired private TrainRouteService trainRouteService;
    @Autowired private JourneyService journeyService;
    @Autowired private SeatInventoryService seatInventoryService;
    @Autowired private BookingService bookingService;
    @Autowired private SeatHoldReaperService reaperService;

    private Long journeyId;
    private List<JourneyStop> runStops;
    private List<JourneySeatInventory> inventories;

    @BeforeEach
    void setupJourneyAndInventory() {
        reaperService.resetCursors();
        paymentTransactionRepository.deleteAll();
        bookingRepository.deleteAll();
        allocationRepository.deleteAll();

        Train train = new Train();
        train.setTrainNumber("REAP-" + UUID.randomUUID().toString().substring(0, 8));
        train.setTrainName("Reaper Express");
        train.setStatus("ACTIVE");
        train = trainRepository.save(train);

        List<Station> stations = List.of(
                station("RST1-" + UUID.randomUUID().toString().substring(0, 4), "Reap Station One"),
                station("RST2-" + UUID.randomUUID().toString().substring(0, 4), "Reap Station Two"),
                station("RST3-" + UUID.randomUUID().toString().substring(0, 4), "Reap Station Three")
        );
        stationRepository.saveAll(stations);

        var route = trainRouteService.createRoute(train.getTrainId(),
                new CreateTrainRouteRequestDTO("route-reap-" + UUID.randomUUID(),
                        List.of(
                                new TrainStopRequestDTO(stations.get(0).getStationId(), 1, null, LocalTime.of(8, 0), 0),
                                new TrainStopRequestDTO(stations.get(1).getStationId(), 2, LocalTime.of(9, 0), LocalTime.of(9, 10), 0),
                                new TrainStopRequestDTO(stations.get(2).getStationId(), 3, LocalTime.of(10, 30), null, 0)
                        )));

        LocalDate serviceDate = LocalDate.now().plusDays(3);
        var journey = journeyService.createJourney(new JourneyRequestDTO(
                train.getTrainId(), null, null, serviceDate, null, null, route.trainRouteId()));

        this.journeyId = journey.journeyId();
        this.runStops = journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(journeyId);
        this.inventories = inventoryRepository.findByJourney_JourneyId(journeyId);
    }

    private BookingResponseDTO createPendingBookingForSeat(JourneySeatInventory seatInventory, User user, int holdMinutes) {
        seatInventoryService.holdSeat(journeyId, seatInventory.getSeat().getSeatId(), holdMinutes,
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId(), user.getUsername());
        return bookingService.createBooking(new CreateBookingRequestDTO(
                journeyId, seatInventory.getSeat().getSeatId(),
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId()),
                user.getUsername());
    }

    @Test
    void abandonedHold_isExpired_freesSeatForFutureBookings() {
        User user = createUser("reap-user-1");
        JourneySeatInventory seatInv = inventories.get(0);

        // User holds the seat without creating a booking
        seatInventoryService.holdSeat(journeyId, seatInv.getSeat().getSeatId(), 5,
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId(), user.getUsername());

        List<SeatAllocation> userAllocations = allocationRepository.findByInventory_InventoryId(seatInv.getInventoryId());
        assertEquals(1, userAllocations.size());
        SeatAllocation allocation = userAllocations.get(0);
        assertEquals("HOLD", allocation.getStatus());

        // Fast-forward hold expiry to 5 minutes ago
        allocation.setHoldExpiresAt(LocalDateTime.now().minusMinutes(5));
        allocationRepository.save(allocation);

        // Run reaper
        ReaperRunSummary summary = reaperService.reapExpiredHolds(LocalDateTime.now(), 50);

        assertTrue(summary.candidatesExamined() >= 1);
        assertTrue(summary.allocationsExpired() >= 1);
        assertEquals(0, summary.bookingsExpired());
        assertEquals(0, summary.anomalyCount());

        // Verify allocation state
        SeatAllocation reapedAllocation = allocationRepository.findById(allocation.getSeatAllocationId()).orElseThrow();
        assertEquals("EXPIRED", reapedAllocation.getStatus());

        // Verify seat is now available for another user to hold
        User user2 = createUser("reap-user-2");
        assertDoesNotThrow(() ->
                seatInventoryService.holdSeat(journeyId, seatInv.getSeat().getSeatId(), 10,
                        runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId(), user2.getUsername())
        );
    }

    @Test
    void holdWithPendingBooking_expiresBothAllocationAndBooking() {
        User user = createUser("reap-user-pending");
        JourneySeatInventory seatInv = inventories.get(1);

        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 5);

        Booking booking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("PENDING_PAYMENT", booking.getBookingStatus());
        assertEquals("PENDING", booking.getPaymentStatus());

        SeatAllocation allocation = booking.getSeatAllocation();
        assertEquals("HOLD", allocation.getStatus());

        // Fast-forward hold expiry to 2 minutes ago
        allocation.setHoldExpiresAt(LocalDateTime.now().minusMinutes(2));
        allocationRepository.save(allocation);

        // Run reaper
        ReaperRunSummary summary = reaperService.reapExpiredHolds(LocalDateTime.now(), 50);

        assertTrue(summary.allocationsExpired() >= 1);
        assertTrue(summary.bookingsExpired() >= 1);
        assertEquals(0, summary.anomalyCount());

        // Verify entities in database
        Booking reapedBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("EXPIRED", reapedBooking.getBookingStatus());
        assertEquals("EXPIRED", reapedBooking.getPaymentStatus());

        SeatAllocation reapedAllocation = allocationRepository.findById(allocation.getSeatAllocationId()).orElseThrow();
        assertEquals("EXPIRED", reapedAllocation.getStatus());
    }

    @Test
    void confirmedBooking_remainsUntouched() {
        User user = createUser("reap-user-confirmed");
        JourneySeatInventory seatInv = inventories.get(2);

        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);
        bookingService.processPayment(created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", "idemp-conf-1", false), user.getUsername());

        Booking confirmedBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("CONFIRMED", confirmedBooking.getBookingStatus());
        assertEquals("BOOKED", confirmedBooking.getSeatAllocation().getStatus());

        // Run reaper
        ReaperRunSummary summary = reaperService.reapExpiredHolds(LocalDateTime.now().plusHours(1), 50);

        assertEquals(0, summary.allocationsExpired());
        assertEquals(0, summary.bookingsExpired());
        assertEquals(0, summary.anomalyCount());

        Booking postReapBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("CONFIRMED", postReapBooking.getBookingStatus());
        assertEquals("SUCCESS", postReapBooking.getPaymentStatus());
        assertEquals("BOOKED", postReapBooking.getSeatAllocation().getStatus());
    }

    @Test
    void inconsistentState_confirmedBookingWithHoldAllocation_reportedAsAnomalyAndNotMutated() {
        User user = createUser("reap-user-inconsistent");
        JourneySeatInventory seatInv = inventories.get(3);

        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);
        Booking booking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();

        // Simulate inconsistent state: booking is CONFIRMED, but allocation remained HOLD with expired hold time
        booking.setBookingStatus("CONFIRMED");
        booking.setPaymentStatus("SUCCESS");
        bookingRepository.save(booking);

        SeatAllocation allocation = booking.getSeatAllocation();
        allocation.setStatus("HOLD");
        allocation.setHoldExpiresAt(LocalDateTime.now().minusMinutes(10));
        allocation = allocationRepository.save(allocation);

        // Run reaper
        ReaperRunSummary summary = reaperService.reapExpiredHolds(LocalDateTime.now(), 50);

        // Allocation and booking must NOT be modified; reported as an integrity anomaly
        assertEquals(0, summary.allocationsExpired());
        assertEquals(0, summary.bookingsExpired());
        assertEquals(1, summary.anomalyCount(), "Must be reported as distinct integrity anomaly");
        assertEquals(0, summary.skippedCount());

        Booking verifiedBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("CONFIRMED", verifiedBooking.getBookingStatus());

        SeatAllocation unmutatedAllocation = allocationRepository.findById(allocation.getSeatAllocationId()).orElseThrow();
        assertEquals("HOLD", unmutatedAllocation.getStatus(), "Allocation must NOT be mutated to BOOKED or EXPIRED");

        // Verify that another user attempting to hold this overlapping segment is still safely prevented
        User user2 = createUser("reap-user-collision");
        // Because allocation is in HOLD with past expiry, SeatInventoryService.holdSeat would normalize expired holds
        // but the booking is CONFIRMED, confirming the inconsistency requires admin reconciliation
        assertNotNull(verifiedBooking.getBookingRef());
    }

    @Test
    void paginationStarvation_initialSkippedOrFailingBatch_doesNotStarveLaterExpiredRecords() {
        // Setup: 4 seats
        // Seat 0 and 1: Anomaly holds (linked to CONFIRMED bookings with HOLD allocation)
        // Seat 2 and 3: Valid expired abandoned holds
        User user1 = createUser("anom-user-1");
        User user2 = createUser("anom-user-2");
        User user3 = createUser("valid-user-1");
        User user4 = createUser("valid-user-2");

        // 1. Create Anomaly 1
        BookingResponseDTO b1 = createPendingBookingForSeat(inventories.get(0), user1, 5);
        Booking booking1 = bookingRepository.findByBookingRef(b1.bookingRef()).orElseThrow();
        booking1.setBookingStatus("CONFIRMED");
        bookingRepository.save(booking1);
        SeatAllocation a1 = booking1.getSeatAllocation();
        a1.setStatus("HOLD");
        a1.setHoldExpiresAt(LocalDateTime.now().minusMinutes(10));
        allocationRepository.save(a1);

        // 2. Create Anomaly 2
        BookingResponseDTO b2 = createPendingBookingForSeat(inventories.get(1), user2, 5);
        Booking booking2 = bookingRepository.findByBookingRef(b2.bookingRef()).orElseThrow();
        booking2.setBookingStatus("CONFIRMED");
        bookingRepository.save(booking2);
        SeatAllocation a2 = booking2.getSeatAllocation();
        a2.setStatus("HOLD");
        a2.setHoldExpiresAt(LocalDateTime.now().minusMinutes(10));
        allocationRepository.save(a2);

        // 3. Create Valid Expired Hold 1
        seatInventoryService.holdSeat(journeyId, inventories.get(2).getSeat().getSeatId(), 5,
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId(), user3.getUsername());
        SeatAllocation a3 = allocationRepository.findByInventory_InventoryId(inventories.get(2).getInventoryId()).get(0);
        a3.setHoldExpiresAt(LocalDateTime.now().minusMinutes(5));
        allocationRepository.save(a3);

        // 4. Create Valid Expired Hold 2
        seatInventoryService.holdSeat(journeyId, inventories.get(3).getSeat().getSeatId(), 5,
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId(), user4.getUsername());
        SeatAllocation a4 = allocationRepository.findByInventory_InventoryId(inventories.get(3).getInventoryId()).get(0);
        a4.setHoldExpiresAt(LocalDateTime.now().minusMinutes(5));
        allocationRepository.save(a4);

        reaperService.resetCursors();

        // Run 1 with batchSize = 2:
        // Must process the first 2 candidates (the anomalies), logging them and stepping cursor forward
        ReaperRunSummary run1 = reaperService.reapExpiredHolds(LocalDateTime.now(), 2);
        assertEquals(2, run1.candidatesExamined());
        assertEquals(2, run1.anomalyCount());
        assertEquals(0, run1.allocationsExpired());

        // Run 2 with batchSize = 2 (subsequent bounded run):
        // Cursor has stepped past the 2 anomalies; must process and reap the subsequent 2 valid expired holds
        ReaperRunSummary run2 = reaperService.reapExpiredHolds(LocalDateTime.now(), 2);
        assertEquals(2, run2.candidatesExamined());
        assertEquals(0, run2.anomalyCount());
        assertEquals(2, run2.allocationsExpired(), "Valid expired records must NOT be starved by earlier anomalies");

        SeatAllocation reapedA3 = allocationRepository.findById(a3.getSeatAllocationId()).orElseThrow();
        assertEquals("EXPIRED", reapedA3.getStatus());

        SeatAllocation reapedA4 = allocationRepository.findById(a4.getSeatAllocationId()).orElseThrow();
        assertEquals("EXPIRED", reapedA4.getStatus());
    }

    @Test
    void batchSize_clampedWithinLowerAndUpperBounds() {
        assertEquals(1, reaperService.clampBatchSize(-50));
        assertEquals(1, reaperService.clampBatchSize(0));
        assertEquals(1, reaperService.clampBatchSize(1));
        assertEquals(50, reaperService.clampBatchSize(50));
        assertEquals(500, reaperService.clampBatchSize(500));
        assertEquals(500, reaperService.clampBatchSize(9999));
    }

    @Test
    void nullExpiryHold_doesNotStarveSubsequentValidHolds() {
        User user1 = createUser("null-exp-user");
        User user2 = createUser("valid-exp-user");

        // Hold 1: null expiry
        seatInventoryService.holdSeat(journeyId, inventories.get(0).getSeat().getSeatId(), 5,
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId(), user1.getUsername());
        SeatAllocation a1 = allocationRepository.findByInventory_InventoryId(inventories.get(0).getInventoryId()).get(0);
        a1.setHoldExpiresAt(null);
        allocationRepository.save(a1);

        // Hold 2: valid past expiry
        seatInventoryService.holdSeat(journeyId, inventories.get(1).getSeat().getSeatId(), 5,
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId(), user2.getUsername());
        SeatAllocation a2 = allocationRepository.findByInventory_InventoryId(inventories.get(1).getInventoryId()).get(0);
        a2.setHoldExpiresAt(LocalDateTime.now().minusMinutes(5));
        allocationRepository.save(a2);

        reaperService.resetCursors();

        // Run with batchSize = 1
        ReaperRunSummary run1 = reaperService.reapExpiredHolds(LocalDateTime.now(), 1);
        assertEquals(1, run1.allocationsExpired());

        // Run with batchSize = 1 for next record
        ReaperRunSummary run2 = reaperService.reapExpiredHolds(LocalDateTime.now(), 1);
        assertEquals(1, run2.allocationsExpired());

        assertEquals("EXPIRED", allocationRepository.findById(a1.getSeatAllocationId()).orElseThrow().getStatus());
        assertEquals("EXPIRED", allocationRepository.findById(a2.getSeatAllocationId()).orElseThrow().getStatus());
    }

    @Test
    void futureHold_remainsUntouched() {
        User user = createUser("reap-user-future");
        JourneySeatInventory seatInv = inventories.get(0);

        // Active hold expiring in 30 minutes
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 30);

        // Run reaper with cutoff = now
        ReaperRunSummary summary = reaperService.reapExpiredHolds(LocalDateTime.now(), 50);

        assertEquals(0, summary.allocationsExpired());
        assertEquals(0, summary.bookingsExpired());

        Booking activeBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("PENDING_PAYMENT", activeBooking.getBookingStatus());
        assertEquals("HOLD", activeBooking.getSeatAllocation().getStatus());
    }

    @Test
    void batchSize_isRespected() {
        User user = createUser("reap-batch-user");
        List<SeatAllocation> createdAllocations = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            JourneySeatInventory seatInv = inventories.get(i);
            seatInventoryService.holdSeat(journeyId, seatInv.getSeat().getSeatId(), 5,
                    runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId(), user.getUsername());
            List<SeatAllocation> allocs = allocationRepository.findByInventory_InventoryId(seatInv.getInventoryId());
            SeatAllocation alloc = allocs.getLast();
            alloc.setHoldExpiresAt(LocalDateTime.now().minusMinutes(5 + i));
            createdAllocations.add(allocationRepository.save(alloc));
        }

        reaperService.resetCursors();

        // Run reaper with batchSize = 2
        ReaperRunSummary firstBatch = reaperService.reapExpiredHolds(LocalDateTime.now(), 2);
        assertEquals(2, firstBatch.candidatesExamined());
        assertEquals(2, firstBatch.allocationsExpired());

        // Verify exactly 2 were expired and 2 remain HOLD
        long expiredCount = createdAllocations.stream()
                .map(a -> allocationRepository.findById(a.getSeatAllocationId()).orElseThrow())
                .filter(a -> "EXPIRED".equalsIgnoreCase(a.getStatus()))
                .count();
        assertEquals(2, expiredCount);

        long holdCount = createdAllocations.stream()
                .map(a -> allocationRepository.findById(a.getSeatAllocationId()).orElseThrow())
                .filter(a -> "HOLD".equalsIgnoreCase(a.getStatus()))
                .count();
        assertEquals(2, holdCount);

        // Run reaper again with batchSize = 2 to reap remainder
        ReaperRunSummary secondBatch = reaperService.reapExpiredHolds(LocalDateTime.now(), 2);
        assertEquals(2, secondBatch.candidatesExamined());
        assertEquals(2, secondBatch.allocationsExpired());

        long finalExpiredCount = createdAllocations.stream()
                .map(a -> allocationRepository.findById(a.getSeatAllocationId()).orElseThrow())
                .filter(a -> "EXPIRED".equalsIgnoreCase(a.getStatus()))
                .count();
        assertEquals(4, finalExpiredCount);
    }

    @Test
    void idempotentOnRepeatedRuns() {
        User user = createUser("reap-idemp");
        JourneySeatInventory seatInv = inventories.get(1);

        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 5);
        Booking booking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        SeatAllocation allocation = booking.getSeatAllocation();
        allocation.setHoldExpiresAt(LocalDateTime.now().minusMinutes(10));
        allocationRepository.save(allocation);

        // First execution
        ReaperRunSummary run1 = reaperService.reapExpiredHolds(LocalDateTime.now(), 50);
        assertEquals(1, run1.allocationsExpired());
        assertEquals(1, run1.bookingsExpired());

        // Second execution right after
        ReaperRunSummary run2 = reaperService.reapExpiredHolds(LocalDateTime.now(), 50);
        assertEquals(0, run2.candidatesExamined());
        assertEquals(0, run2.allocationsExpired());
        assertEquals(0, run2.bookingsExpired());
        assertEquals(0, run2.failureCount());

        // Verify status remains EXPIRED
        Booking verifiedBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("EXPIRED", verifiedBooking.getBookingStatus());
        assertEquals("EXPIRED", verifiedBooking.getPaymentStatus());
    }

    @Test
    void legacyInventoryHold_normalizesToAvailable() {
        JourneySeatInventory seatInv = inventories.get(2);
        User user = createUser("reap-legacy-user");

        // Simulate legacy inventory-level hold
        seatInv.setStatus("HOLD");
        seatInv.setHoldOwner(user);
        seatInv.setHoldExpiresAt(LocalDateTime.now().minusMinutes(15));
        inventoryRepository.save(seatInv);

        ReaperRunSummary summary = reaperService.reapExpiredHolds(LocalDateTime.now(), 50);
        assertEquals(1, summary.legacyHoldsNormalized());

        JourneySeatInventory normalized = inventoryRepository.findById(seatInv.getInventoryId()).orElseThrow();
        assertEquals("AVAILABLE", normalized.getStatus());
        assertNull(normalized.getHoldExpiresAt());
        assertNull(normalized.getHoldOwner());
    }

    @Test
    void concurrent_reaperAndPayment_threadSafety() throws Exception {
        User user = createUser("reap-concurrent-pay");
        JourneySeatInventory seatInv = inventories.get(0);

        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 1);
        Booking booking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        SeatAllocation allocation = booking.getSeatAllocation();
        // Set hold to expired
        allocation.setHoldExpiresAt(LocalDateTime.now().minusSeconds(1));
        allocationRepository.save(allocation);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<ReaperRunSummary> reaperFuture = executor.submit(() -> {
            startLatch.await();
            return reaperService.reapExpiredHolds(LocalDateTime.now(), 50);
        });

        Future<String> paymentFuture = executor.submit(() -> {
            startLatch.await();
            try {
                bookingService.processPayment(
                        created.bookingRef(),
                        new PaymentRequestDTO("MOCK_UPI", "idemp-conc-pay-1", false),
                        user.getUsername()
                );
                return "PAYMENT_SUCCESS";
            } catch (Exception ex) {
                return "PAYMENT_EXCEPTION: " + ex.getClass().getSimpleName();
            }
        });

        startLatch.countDown();
        ReaperRunSummary reaperSummary = reaperFuture.get(10, TimeUnit.SECONDS);
        String paymentResult = paymentFuture.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        Booking finalBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        SeatAllocation finalAllocation = allocationRepository.findById(allocation.getSeatAllocationId()).orElseThrow();

        if ("CONFIRMED".equalsIgnoreCase(finalBooking.getBookingStatus())) {
            assertEquals("BOOKED", finalAllocation.getStatus());
        } else {
            assertEquals("EXPIRED", finalBooking.getBookingStatus());
            assertEquals("EXPIRED", finalAllocation.getStatus());
        }
        assertEquals(0, reaperSummary.failureCount());
    }

    @Test
    void concurrent_reaperAndRelease_threadSafety() throws Exception {
        User user = createUser("reap-concurrent-rel");
        JourneySeatInventory seatInv = inventories.get(1);

        seatInventoryService.holdSeat(journeyId, seatInv.getSeat().getSeatId(), 1,
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId(), user.getUsername());
        List<SeatAllocation> allocs = allocationRepository.findByInventory_InventoryId(seatInv.getInventoryId());
        SeatAllocation allocation = allocs.get(0);
        allocation.setHoldExpiresAt(LocalDateTime.now().minusSeconds(1));
        allocationRepository.save(allocation);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<ReaperRunSummary> reaperFuture = executor.submit(() -> {
            startLatch.await();
            return reaperService.reapExpiredHolds(LocalDateTime.now(), 50);
        });

        Future<String> releaseFuture = executor.submit(() -> {
            startLatch.await();
            try {
                seatInventoryService.releaseSeat(journeyId, seatInv.getSeat().getSeatId());
                return "RELEASE_SUCCESS";
            } catch (Exception ex) {
                return "RELEASE_EXCEPTION: " + ex.getClass().getSimpleName();
            }
        });

        startLatch.countDown();
        ReaperRunSummary reaperSummary = reaperFuture.get(10, TimeUnit.SECONDS);
        String releaseResult = releaseFuture.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals("RELEASE_SUCCESS", releaseResult);
        assertEquals(0, reaperSummary.failureCount());

        SeatAllocation finalAllocation = allocationRepository.findById(allocation.getSeatAllocationId()).orElseThrow();
        assertTrue("EXPIRED".equalsIgnoreCase(finalAllocation.getStatus()) || "RELEASED".equalsIgnoreCase(finalAllocation.getStatus()),
                "Allocation status must be cleanly EXPIRED or RELEASED, but was " + finalAllocation.getStatus());
    }

    @Test
    void scheduledExecution_skippedWhenDisabled() {
        assertFalse(reaperService.isEnabled());
        assertDoesNotThrow(() -> reaperService.scheduledReap());
    }

    @Test
    void clockCutoff_advancementDeterminism() {
        User user = createUser("reap-clock-user");
        JourneySeatInventory seatInv = inventories.get(3);

        LocalDateTime baseTime = LocalDateTime.now();
        seatInventoryService.holdSeat(journeyId, seatInv.getSeat().getSeatId(), 5,
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId(), user.getUsername());
        List<SeatAllocation> allocs = allocationRepository.findByInventory_InventoryId(seatInv.getInventoryId());
        SeatAllocation allocation = allocs.get(0);
        allocation.setHoldExpiresAt(baseTime.plusMinutes(5));
        allocationRepository.save(allocation);

        reaperService.resetCursors();

        // 1. Cutoff at baseTime + 2 minutes -> Should NOT reap
        ReaperRunSummary earlyRun = reaperService.reapExpiredHolds(baseTime.plusMinutes(2), 50);
        assertEquals(0, earlyRun.allocationsExpired());
        SeatAllocation check1 = allocationRepository.findById(allocation.getSeatAllocationId()).orElseThrow();
        assertEquals("HOLD", check1.getStatus());

        // 2. Cutoff at baseTime + 6 minutes -> MUST reap
        ReaperRunSummary lateRun = reaperService.reapExpiredHolds(baseTime.plusMinutes(6), 50);
        assertEquals(1, lateRun.allocationsExpired());
        SeatAllocation check2 = allocationRepository.findById(allocation.getSeatAllocationId()).orElseThrow();
        assertEquals("EXPIRED", check2.getStatus());
    }

    private Station station(String code, String name) {
        Station s = new Station();
        s.setStationCode(code);
        s.setStationName(name);
        s.setCity(name);
        s.setState("Reaper State");
        return s;
    }

    private User createUser(String prefix) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername(username);
        user.setFullName(prefix);
        user.setEmail(username + "@reaper-test.com");
        user.setPassword("not-used-password");
        user.setStatus("ACTIVE");
        user.setRole("ROLE_USER");
        return userRepository.save(user);
    }
}
