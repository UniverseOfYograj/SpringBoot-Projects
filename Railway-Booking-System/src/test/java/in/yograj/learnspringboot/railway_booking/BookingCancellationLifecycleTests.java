package in.yograj.learnspringboot.railway_booking;

import in.yograj.learnspringboot.railway_booking.dto.*;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.*;
import in.yograj.learnspringboot.railway_booking.service.BookingService;
import in.yograj.learnspringboot.railway_booking.service.JourneyService;
import in.yograj.learnspringboot.railway_booking.service.SeatInventoryService;
import in.yograj.learnspringboot.railway_booking.service.TrainRouteService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=${test.datasource.url:jdbc:h2:mem:cancellation-lifecycle-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL}",
        "spring.datasource.driver-class-name=${test.datasource.driver-class-name:org.h2.Driver}",
        "spring.datasource.username=${test.datasource.username:sa}",
        "spring.datasource.password=${test.datasource.password:${DB_PASSWORD:}}",
        "spring.jpa.hibernate.ddl-auto=${test.jpa.ddl-auto:create-drop}",
        "spring.flyway.enabled=false",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length"
})
class BookingCancellationLifecycleTests {

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    @Autowired private DataSource dataSource;
    @Autowired private TrainRepository trainRepository;
    @Autowired private StationRepository stationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JourneySeatInventoryRepository inventoryRepository;
    @Autowired private JourneyStopRepository journeyStopRepository;
    @Autowired private SeatAllocationRepository allocationRepository;
    @Autowired private BookingRepository bookingRepository;
    @MockitoSpyBean private PaymentTransactionRepository paymentTransactionRepository;
    @Autowired private TrainRouteService trainRouteService;
    @Autowired private JourneyService journeyService;
    @Autowired private SeatInventoryService seatInventoryService;
    @Autowired private BookingService bookingService;

    private Long journeyId;
    private List<JourneyStop> runStops;
    private List<JourneySeatInventory> inventories;

    @BeforeAll
    static void verifyDatabaseTarget(@Autowired DataSource dataSource) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            String url = conn.getMetaData().getURL();
            if (url != null && url.startsWith("jdbc:h2:")) {
                return;
            }
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT current_database()")) {
                if (rs.next()) {
                    String currentDb = rs.getString(1);
                    if ("railway_booking".equalsIgnoreCase(currentDb)) {
                        throw new IllegalStateException(
                                "FATAL SAFETY VIOLATION: Test process connected to protected database 'railway_booking'!");
                    }
                }
            }
        }
    }

    @BeforeEach
    void setupJourneyAndInventory() {
        Train train = new Train();
        train.setTrainNumber("CNC-" + UUID.randomUUID().toString().substring(0, 8));
        train.setTrainName("Cancellation Test Express");
        train.setStatus("ACTIVE");
        train = trainRepository.save(train);

        List<Station> stations = List.of(
                station("CA1-" + UUID.randomUUID().toString().substring(0, 5), "Origin Station"),
                station("CA2-" + UUID.randomUUID().toString().substring(0, 5), "Middle Station"),
                station("CA3-" + UUID.randomUUID().toString().substring(0, 5), "Destination Station")
        );
        stationRepository.saveAll(stations);

        var route = trainRouteService.createRoute(train.getTrainId(),
                new CreateTrainRouteRequestDTO("route-" + UUID.randomUUID(),
                        List.of(
                                new TrainStopRequestDTO(stations.get(0).getStationId(), 1, null, LocalTime.of(10, 0), 0),
                                new TrainStopRequestDTO(stations.get(1).getStationId(), 2, LocalTime.of(11, 0), LocalTime.of(11, 10), 0),
                                new TrainStopRequestDTO(stations.get(2).getStationId(), 3, LocalTime.of(12, 30), null, 0)
                        )));

        LocalDate serviceDate = LocalDate.now().plusDays(3);
        var journey = journeyService.createJourney(new JourneyRequestDTO(
                train.getTrainId(), null, null, serviceDate, null, null, route.trainRouteId()));
        this.journeyId = journey.journeyId();
        this.runStops = journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(journeyId);
        runStops.get(0).setDistanceFromOriginKm(BigDecimal.ZERO);
        runStops.get(1).setDistanceFromOriginKm(new BigDecimal("100.00"));
        runStops.get(2).setDistanceFromOriginKm(new BigDecimal("200.00"));
        journeyStopRepository.saveAll(runStops);
        this.runStops = journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(journeyId);
        this.inventories = inventoryRepository.findByJourney_JourneyId(journeyId);
    }

    private User createUser(String usernamePrefix, String role) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername(usernamePrefix + "-" + unique);
        user.setPassword("password123");
        user.setEmail(usernamePrefix + "-" + unique + "@test.local");
        user.setPhoneNumber("9" + unique.substring(0, 7) + "00");
        user.setFullName("Test User " + unique);
        user.setRole(role);
        user.setStatus("ACTIVE");
        user.setCreatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    private Station station(String code, String name) {
        Station station = new Station();
        station.setStationCode(code);
        station.setStationName(name);
        return station;
    }

    private BookingResponseDTO createPendingBooking(JourneySeatInventory seatInv, User user) {
        seatInventoryService.holdSeat(journeyId, seatInv.getSeat().getSeatId(), 10,
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId(), user.getUsername());
        return bookingService.createBooking(new CreateBookingRequestDTO(
                journeyId, seatInv.getSeat().getSeatId(),
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId()),
                user.getUsername());
    }

    @Test
    void cancelBooking_confirmedBooking_successAndReleasesSeatForSubsequentUser() {
        User user1 = createUser("user1", "ROLE_USER");
        User user2 = createUser("user2", "ROLE_USER");
        JourneySeatInventory seatInv = inventories.get(0);

        // 1. User 1 books and confirms
        BookingResponseDTO created = createPendingBooking(seatInv, user1);
        PaymentResponseDTO paymentRes = bookingService.processPayment(
                created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", "idemp-cancel-1", false), user1.getUsername());
        assertEquals("CONFIRMED", paymentRes.bookingStatus());
        assertEquals("SUCCESS", paymentRes.paymentStatus());

        // 2. User 2 cannot hold the seat because it is BOOKED
        assertThrows(BadRequestException.class, () ->
                seatInventoryService.holdSeat(journeyId, seatInv.getSeat().getSeatId(), 10,
                        runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId(), user2.getUsername())
        );

        // 3. User 1 cancels the booking
        BookingDetailResponseDTO cancelled = bookingService.cancelBooking(created.bookingRef(), user1.getUsername());
        assertEquals("CANCELLED", cancelled.bookingStatus());
        assertEquals("REFUNDED", cancelled.paymentStatus());

        // 4. Verify DB state & refund ledger
        Booking dbBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("CANCELLED", dbBooking.getBookingStatus());
        assertEquals("REFUNDED", dbBooking.getPaymentStatus());
        SeatAllocation allocation = dbBooking.getSeatAllocation();
        assertNotNull(allocation);
        assertEquals("CANCELLED", allocation.getStatus());

        List<PaymentTransaction> txns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
        assertEquals(2, txns.size(), "Must contain original payment and mock refund transaction");
        PaymentTransaction refundTxn = txns.stream()
                .filter(t -> "REFUNDED".equalsIgnoreCase(t.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Refund transaction must exist"));
        assertEquals(paymentRes.amount(), refundTxn.getAmount());
        assertTrue(refundTxn.getTransactionRef().startsWith("TXN-REFUND-"));
        assertEquals("REFUND-" + created.bookingRef(), refundTxn.getIdempotencyKey());
        assertEquals("MOCK_UPI", refundTxn.getPaymentMethod());
        assertFalse(Boolean.TRUE.equals(refundTxn.getSimulateFailure()));
        assertNotNull(refundTxn.getCreatedAt());

        // 5. User 2 can now successfully hold and book the released seat!
        var user2Hold = seatInventoryService.holdSeat(journeyId, seatInv.getSeat().getSeatId(), 10,
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId(), user2.getUsername());
        assertNotNull(user2Hold);
        assertEquals("HOLD", user2Hold.status());

        BookingResponseDTO user2Booking = bookingService.createBooking(new CreateBookingRequestDTO(
                journeyId, seatInv.getSeat().getSeatId(),
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId()),
                user2.getUsername());
        assertEquals("PENDING_PAYMENT", user2Booking.bookingStatus());
    }

    @Test
    void cancelBooking_pendingPaymentBooking_successAndReleasesHold() {
        User user1 = createUser("user-pending", "ROLE_USER");
        User user2 = createUser("user-taker", "ROLE_USER");
        JourneySeatInventory seatInv = inventories.get(0);

        BookingResponseDTO created = createPendingBooking(seatInv, user1);
        assertEquals("PENDING_PAYMENT", created.bookingStatus());

        // Cancel pending booking
        BookingDetailResponseDTO cancelled = bookingService.cancelBooking(created.bookingRef(), user1.getUsername());
        assertEquals("CANCELLED", cancelled.bookingStatus());
        assertEquals("CANCELLED", cancelled.paymentStatus());

        // Allocation should be CANCELLED
        Booking dbBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("CANCELLED", dbBooking.getBookingStatus());
        assertEquals("CANCELLED", dbBooking.getSeatAllocation().getStatus());

        // User 2 can hold immediately
        var user2Hold = seatInventoryService.holdSeat(journeyId, seatInv.getSeat().getSeatId(), 10,
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId(), user2.getUsername());
        assertEquals("HOLD", user2Hold.status());
    }

    @Test
    void cancelBooking_unauthorizedUser_throwsAccessDeniedException() {
        User owner = createUser("owner", "ROLE_USER");
        User attacker = createUser("attacker", "ROLE_USER");
        JourneySeatInventory seatInv = inventories.get(0);

        BookingResponseDTO created = createPendingBooking(seatInv, owner);
        bookingService.processPayment(created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", "idemp-auth-1", false), owner.getUsername());

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                bookingService.cancelBooking(created.bookingRef(), attacker.getUsername())
        );
        assertEquals("You are not authorized to cancel this booking", ex.getMessage());

        // Verify booking remains CONFIRMED
        Booking dbBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("CONFIRMED", dbBooking.getBookingStatus());
        assertEquals("BOOKED", dbBooking.getSeatAllocation().getStatus());
    }

    @Test
    void cancelBooking_adminUser_canCancelAnyBooking() {
        User customer = createUser("customer", "ROLE_USER");
        User admin = createUser("admin-user", "ROLE_ADMIN");
        JourneySeatInventory seatInv = inventories.get(0);

        BookingResponseDTO created = createPendingBooking(seatInv, customer);
        bookingService.processPayment(created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", "idemp-adm-1", false), customer.getUsername());

        BookingDetailResponseDTO cancelled = bookingService.cancelBooking(created.bookingRef(), admin.getUsername());
        assertEquals("CANCELLED", cancelled.bookingStatus());

        Booking dbBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("CANCELLED", dbBooking.getBookingStatus());
        assertEquals("CANCELLED", dbBooking.getSeatAllocation().getStatus());
    }

    @Test
    void cancelBooking_repeatedCancellation_throwsIllegalStateException() {
        User user = createUser("repeat-cancel", "ROLE_USER");
        JourneySeatInventory seatInv = inventories.get(0);

        BookingResponseDTO created = createPendingBooking(seatInv, user);
        bookingService.processPayment(created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", "idemp-rep-1", false), user.getUsername());

        // First cancel succeeds
        bookingService.cancelBooking(created.bookingRef(), user.getUsername());

        // Second cancel throws 409 IllegalStateException
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                bookingService.cancelBooking(created.bookingRef(), user.getUsername())
        );
        assertEquals("Booking has already been cancelled", ex.getMessage());

        // Verify exactly one refund ledger record was created despite repeated cancellation attempt
        List<PaymentTransaction> txns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
        long refundCount = txns.stream().filter(t -> "REFUNDED".equalsIgnoreCase(t.getStatus())).count();
        assertEquals(1, refundCount, "Repeated cancel attempt must not create duplicate refund ledger entries");
    }

    @Test
    void cancelBooking_expiredBooking_throwsIllegalStateException() {
        User user = createUser("expired-user", "ROLE_USER");
        JourneySeatInventory seatInv = inventories.get(0);

        BookingResponseDTO created = createPendingBooking(seatInv, user);
        Booking dbBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        dbBooking.setBookingStatus("EXPIRED");
        bookingRepository.save(dbBooking);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                bookingService.cancelBooking(created.bookingRef(), user.getUsername())
        );
        assertTrue(ex.getMessage().contains("Booking cannot be cancelled in status: EXPIRED"));
    }

    @Test
    void cancelBooking_concurrentAttempts_onlyOneSucceeds() throws Exception {
        User user = createUser("concurrent-cancel", "ROLE_USER");
        JourneySeatInventory seatInv = inventories.get(0);

        BookingResponseDTO created = createPendingBooking(seatInv, user);
        bookingService.processPayment(created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", "idemp-conc-1", false), user.getUsername());

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            results.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                try {
                    bookingService.cancelBooking(created.bookingRef(), user.getUsername());
                    return "SUCCESS";
                } catch (IllegalStateException e) {
                    return "CONFLICT: " + e.getMessage();
                }
            }));
        }

        readyLatch.await();
        startLatch.countDown();

        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get());
        }
        executor.shutdown();

        long successCount = outcomes.stream().filter(o -> o.equals("SUCCESS")).count();
        long conflictCount = outcomes.stream().filter(o -> o.startsWith("CONFLICT")).count();

        assertEquals(1, successCount, "Exactly one concurrent cancellation must succeed");
        assertEquals(1, conflictCount, "Second concurrent cancellation must be rejected with conflict");

        Booking finalBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("CANCELLED", finalBooking.getBookingStatus());
        assertEquals("CANCELLED", finalBooking.getSeatAllocation().getStatus());
    }

    @Test
    void cancelBooking_missingOriginalPaymentTransaction_failsAndRollsBack() {
        User user = createUser("unverif-missing", "ROLE_USER");
        JourneySeatInventory seatInv = inventories.get(0);

        BookingResponseDTO created = createPendingBooking(seatInv, user);
        bookingService.processPayment(created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", "idemp-unverif-1", false), user.getUsername());

        // Delete the original payment transaction to simulate missing ledger record
        List<PaymentTransaction> txns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
        assertEquals(1, txns.size());
        paymentTransactionRepository.deleteAll(txns);

        // Attempt cancellation: must fail safely because paid amount cannot be established
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                bookingService.cancelBooking(created.bookingRef(), user.getUsername())
        );
        assertTrue(ex.getMessage().contains("original successful payment transaction not found"));

        // Verify transaction rollback: booking remains CONFIRMED, paymentStatus remains SUCCESS, seat remains BOOKED
        Booking dbBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("CONFIRMED", dbBooking.getBookingStatus());
        assertEquals("SUCCESS", dbBooking.getPaymentStatus());
        assertEquals("BOOKED", dbBooking.getSeatAllocation().getStatus());

        List<PaymentTransaction> postTxns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
        assertTrue(postTxns.isEmpty(), "No refund transaction should have been recorded");
    }

    @Test
    void cancelBooking_mismatchedPaymentAmount_failsAndRollsBack() {
        User user = createUser("unverif-mismatch", "ROLE_USER");
        JourneySeatInventory seatInv = inventories.get(0);

        BookingResponseDTO created = createPendingBooking(seatInv, user);
        bookingService.processPayment(created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", "idemp-unverif-2", false), user.getUsername());

        // Tamper with booking amount so it contradicts the payment transaction
        Booking dbBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        dbBooking.setAmount(new BigDecimal("999.00"));
        bookingRepository.save(dbBooking);

        // Attempt cancellation: must fail safely because booking amount contradicts payment transaction
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                bookingService.cancelBooking(created.bookingRef(), user.getUsername())
        );
        assertTrue(ex.getMessage().contains("does not match original payment transaction amount"));

        // Verify transaction rollback: booking remains CONFIRMED, allocation remains BOOKED, no refund txn created
        Booking rolledBackBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("CONFIRMED", rolledBackBooking.getBookingStatus());
        assertEquals("SUCCESS", rolledBackBooking.getPaymentStatus());
        assertEquals("BOOKED", rolledBackBooking.getSeatAllocation().getStatus());

        List<PaymentTransaction> postTxns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
        assertEquals(1, postTxns.size(), "Only original payment transaction must exist");
        assertEquals("SUCCESS", postTxns.get(0).getStatus());
    }

    @Test
    void cancelBooking_nullBookingAmount_prefersOriginalTransactionAmount() {
        User user = createUser("null-amt-user", "ROLE_USER");
        JourneySeatInventory seatInv = inventories.get(0);

        BookingResponseDTO created = createPendingBooking(seatInv, user);
        bookingService.processPayment(created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", "idemp-null-amt", false), user.getUsername());

        // Update payment transaction amount to a non-default value, and set booking.amount to null
        PaymentTransaction txn = paymentTransactionRepository.findByBookingRef(created.bookingRef()).get(0);
        BigDecimal customAmount = new BigDecimal("750.00");
        txn.setAmount(customAmount);
        paymentTransactionRepository.save(txn);

        Booking dbBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        dbBooking.setAmount(null);
        bookingRepository.save(dbBooking);

        // Cancellation should succeed by preferring the original transaction amount instead of default mock fare
        BookingDetailResponseDTO cancelled = bookingService.cancelBooking(created.bookingRef(), user.getUsername());
        assertEquals("CANCELLED", cancelled.bookingStatus());
        assertEquals("REFUNDED", cancelled.paymentStatus());
        assertEquals(customAmount, cancelled.amount());

        // Verify refund transaction in DB has exact customAmount
        List<PaymentTransaction> postTxns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
        PaymentTransaction refundTxn = postTxns.stream()
                .filter(t -> "REFUNDED".equalsIgnoreCase(t.getStatus()))
                .findFirst()
                .orElseThrow();
        assertEquals(customAmount, refundTxn.getAmount());
    }

    @Test
    void cancelBooking_failureDuringRefundLedgerSave_rollsBackSeatReleaseAndBookingStatus() {
        User user = createUser("rollback-user", "ROLE_USER");
        JourneySeatInventory seatInv = inventories.get(0);

        BookingResponseDTO created = createPendingBooking(seatInv, user);
        bookingService.processPayment(created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", "idemp-rb-save", false), user.getUsername());

        // Verify pre-cancellation confirmed state
        Booking initialBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("CONFIRMED", initialBooking.getBookingStatus());
        assertEquals("SUCCESS", initialBooking.getPaymentStatus());
        assertEquals("BOOKED", initialBooking.getSeatAllocation().getStatus());
        Long allocationId = initialBooking.getSeatAllocation().getSeatAllocationId();

        // Capture snapshot of mutable inventory fields affected by cancelBooking
        JourneySeatInventory preInv = inventoryRepository.findById(seatInv.getInventoryId()).orElseThrow();
        String expectedInvStatus = preInv.getStatus();
        LocalDateTime expectedHoldExpiresAt = preInv.getHoldExpiresAt();
        Long expectedHoldOwnerId = preInv.getHoldOwner() != null ? preInv.getHoldOwner().getUserId() : null;
        LocalDateTime expectedUpdatedAt = preInv.getUpdatedAt();

        // Inject simulated failure during refund ledger persistence (Step 7), which occurs AFTER
        // seat allocation has already been updated to CANCELLED in Step 6
        doThrow(new RuntimeException("Simulated ledger failure on refund persistence"))
                .when(paymentTransactionRepository)
                .save(argThat(txn -> txn != null && "REFUNDED".equalsIgnoreCase(txn.getStatus())));

        try {
            // Execution must fail at the late refund ledger save step
            RuntimeException ex = assertThrows(RuntimeException.class, () ->
                    bookingService.cancelBooking(created.bookingRef(), user.getUsername())
            );
            assertTrue(ex.getMessage().contains("Simulated ledger failure on refund persistence"));

            // Fresh database reads to verify transaction rollback of dirty state:
            // 1. Booking status and payment status must roll back to CONFIRMED / SUCCESS
            Booking dbBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
            assertEquals("CONFIRMED", dbBooking.getBookingStatus(), "Booking status must be rolled back to CONFIRMED");
            assertEquals("SUCCESS", dbBooking.getPaymentStatus(), "Payment status must be rolled back to SUCCESS");

            // 2. Seat allocation must roll back to BOOKED despite in-flight mutation to CANCELLED in Step 6
            SeatAllocation dbAllocation = allocationRepository.findById(allocationId).orElseThrow();
            assertEquals("BOOKED", dbAllocation.getStatus(), "Seat allocation status must be rolled back to BOOKED");

            // 3. Parent inventory mutable fields affected by cancelBooking must roll back to original snapshot
            JourneySeatInventory dbInv = inventoryRepository.findById(seatInv.getInventoryId()).orElseThrow();
            assertEquals(expectedInvStatus, dbInv.getStatus(), "Inventory status must match pre-cancellation snapshot");
            assertEquals(expectedHoldExpiresAt, dbInv.getHoldExpiresAt(), "Inventory holdExpiresAt must match pre-cancellation snapshot");
            Long actualHoldOwnerId = dbInv.getHoldOwner() != null ? dbInv.getHoldOwner().getUserId() : null;
            assertEquals(expectedHoldOwnerId, actualHoldOwnerId, "Inventory holdOwner must match pre-cancellation snapshot");
            assertEquals(expectedUpdatedAt, dbInv.getUpdatedAt(), "Inventory updatedAt must match pre-cancellation snapshot");

            // 4. No refund transaction must remain in the payment transaction ledger
            List<PaymentTransaction> postTxns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
            assertEquals(1, postTxns.size(), "Only original successful payment transaction must exist");
            assertEquals("SUCCESS", postTxns.get(0).getStatus());
        } finally {
            reset(paymentTransactionRepository);
        }
    }
}
