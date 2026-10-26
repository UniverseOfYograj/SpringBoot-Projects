package in.yograj.learnspringboot.railway_booking;

import in.yograj.learnspringboot.railway_booking.dto.BookingResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.CreateBookingRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.CreateTrainRouteRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.JourneyRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainStopRequestDTO;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.SeatHoldExpiredException;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.*;
import in.yograj.learnspringboot.railway_booking.service.BookingService;
import in.yograj.learnspringboot.railway_booking.service.JourneyService;
import in.yograj.learnspringboot.railway_booking.service.SeatInventoryService;
import in.yograj.learnspringboot.railway_booking.service.TrainRouteService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
        "spring.datasource.url=${test.datasource.url:jdbc:h2:mem:payment-lifecycle-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL}",
        "spring.datasource.driver-class-name=${test.datasource.driver-class-name:org.h2.Driver}",
        "spring.datasource.username=${test.datasource.username:sa}",
        "spring.datasource.password=${test.datasource.password:${DB_PASSWORD:}}",
        "spring.jpa.hibernate.ddl-auto=${test.jpa.ddl-auto:create-drop}",
        "spring.flyway.enabled=${test.flyway.enabled:false}",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length"
})
@ContextConfiguration(initializers = BookingPaymentLifecycleTests.FlywayTestInitializer.class)
class BookingPaymentLifecycleTests {

    static class FlywayTestInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            var env = context.getEnvironment();
            boolean flywayEnabled = Boolean.parseBoolean(env.getProperty("spring.flyway.enabled", "false"));
            if (!flywayEnabled) {
                return;
            }
            String url = env.getProperty("spring.datasource.url");
            if (url == null || url.startsWith("jdbc:h2:")) {
                return;
            }
            if (url.contains("railway_booking") && !url.contains("railway_booking_test")) {
                throw new IllegalStateException(
                        "FATAL SAFETY VIOLATION: Flyway migration attempted against protected database 'railway_booking'!");
            }
            if (!url.contains("railway_booking_test")) {
                throw new IllegalStateException(
                        "FATAL SAFETY VIOLATION: Flyway migration attempted against unknown database '" + url + "'!");
            }
            String user = env.getProperty("spring.datasource.username", "postgres");
            String password = env.getProperty("spring.datasource.password", "");

            Flyway flyway = Flyway.configure()
                    .dataSource(url, user, password)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(false)
                    .load();
            flyway.migrate();
        }
    }

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
    @Autowired private PaymentTransactionRepository paymentTransactionRepository;
    @Autowired private TrainRouteService trainRouteService;
    @Autowired private JourneyService journeyService;
    @Autowired private SeatInventoryService seatInventoryService;
    @Autowired private BookingService bookingService;

    private Long journeyId;
    private List<JourneyStop> runStops;
    private List<JourneySeatInventory> inventories;

    @BeforeAll
    static void verifyDatabaseTarget(@Autowired DataSource dataSource) throws Exception {
        assertSafeDatabaseTarget(dataSource);
    }

    private static void assertSafeDatabaseTarget(DataSource ds) throws Exception {
        try (Connection conn = ds.getConnection()) {
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
                    if (!"railway_booking_test".equalsIgnoreCase(currentDb)) {
                        throw new IllegalStateException(
                                "FATAL SAFETY VIOLATION: Test process connected to unexpected database '"
                                        + currentDb + "' instead of 'railway_booking_test'!");
                    }
                } else {
                    throw new IllegalStateException("FATAL SAFETY VIOLATION: Failed to query current_database()!");
                }
            }
        }
    }

    @BeforeEach
    void setupJourneyAndInventory() throws Exception {
        assertSafeDatabaseTarget(dataSource);
        Train train = new Train();
        train.setTrainNumber("PAY-" + UUID.randomUUID().toString().substring(0, 8));
        train.setTrainName("Payment Express");
        train.setStatus("ACTIVE");
        train = trainRepository.save(train);

        List<Station> stations = List.of(
                station("ST1-" + UUID.randomUUID().toString().substring(0, 5), "Station One"),
                station("ST2-" + UUID.randomUUID().toString().substring(0, 5), "Station Two"),
                station("ST3-" + UUID.randomUUID().toString().substring(0, 5), "Station Three")
        );
        stationRepository.saveAll(stations);

        var route = trainRouteService.createRoute(train.getTrainId(),
                new CreateTrainRouteRequestDTO("route-" + UUID.randomUUID(),
                        List.of(
                                new TrainStopRequestDTO(stations.get(0).getStationId(), 1, null, LocalTime.of(10, 0), 0),
                                new TrainStopRequestDTO(stations.get(1).getStationId(), 2, LocalTime.of(11, 0), LocalTime.of(11, 10), 0),
                                new TrainStopRequestDTO(stations.get(2).getStationId(), 3, LocalTime.of(12, 30), null, 0)
                        )));

        LocalDate serviceDate = LocalDate.now().plusDays(2);
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
    void successfulPayment_confirmsBookingAndBooksSeatAllocation() {
        User user = createUser("pay-success");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        PaymentRequestDTO paymentRequest = new PaymentRequestDTO("MOCK_UPI", "idemp-001", false);
        PaymentResponseDTO response = bookingService.processPayment(
                created.bookingRef(), paymentRequest, user.getUsername());

        // Verify response DTO
        assertNotNull(response.transactionRef());
        assertTrue(response.transactionRef().startsWith("TXN-"));
        assertEquals(created.bookingRef(), response.bookingRef());
        assertEquals("SUCCESS", response.paymentStatus());
        assertEquals("CONFIRMED", response.bookingStatus());
        assertEquals(new BigDecimal("500.00"), response.amount());

        // Verify database entities
        Booking updatedBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("CONFIRMED", updatedBooking.getBookingStatus());
        assertEquals("SUCCESS", updatedBooking.getPaymentStatus());
        assertEquals(new BigDecimal("500.00"), updatedBooking.getAmount());

        Long allocationId = updatedBooking.getSeatAllocation().getSeatAllocationId();
        SeatAllocation allocation = allocationRepository.findById(allocationId).orElseThrow();
        assertEquals("BOOKED", allocation.getStatus());

        List<PaymentTransaction> transactions = paymentTransactionRepository.findByBookingRef(created.bookingRef());
        assertEquals(1, transactions.size());
        PaymentTransaction txn = transactions.get(0);
        assertEquals("SUCCESS", txn.getStatus());
        assertEquals("MOCK_UPI", txn.getPaymentMethod());
        assertEquals(new BigDecimal("500.00"), txn.getAmount());
        assertNull(txn.getFailureReason());
    }

    @Test
    void expiredHold_rejectsPaymentAndMarksBookingAndAllocationExpired() {
        User user = createUser("pay-expired");
        JourneySeatInventory seatInv = inventories.get(1);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        // Manually fast-forward hold expiry to the past
        Booking pendingBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        Long allocationId = pendingBooking.getSeatAllocation().getSeatAllocationId();
        SeatAllocation allocation = allocationRepository.findById(allocationId).orElseThrow();
        allocation.setHoldExpiresAt(LocalDateTime.now().minusSeconds(10));
        allocationRepository.save(allocation);

        PaymentRequestDTO request = new PaymentRequestDTO("MOCK_CARD", null, false);
        SeatHoldExpiredException exception = assertThrows(SeatHoldExpiredException.class, () ->
                bookingService.processPayment(created.bookingRef(), request, user.getUsername()));

        assertTrue(exception.getMessage().contains("Seat hold has expired"));

        // Verify state is EXPIRED and NOT CONFIRMED
        Booking booking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("EXPIRED", booking.getBookingStatus());
        assertEquals("EXPIRED", booking.getPaymentStatus());

        SeatAllocation updatedAllocation = allocationRepository.findById(allocationId).orElseThrow();
        assertEquals("EXPIRED", updatedAllocation.getStatus());

        List<PaymentTransaction> txns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
        assertEquals(1, txns.size());
        assertEquals("FAILED", txns.get(0).getStatus());
        assertEquals("Seat hold has expired", txns.get(0).getFailureReason());
    }

    @Test
    void failedPayment_recordsFailedTransactionAndKeepsBookingPending() {
        User user = createUser("pay-failed");
        JourneySeatInventory seatInv = inventories.get(2);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        PaymentRequestDTO failedRequest = new PaymentRequestDTO("MOCK_NET_BANKING", "idemp-fail-1", true);
        PaymentResponseDTO response = bookingService.processPayment(
                created.bookingRef(), failedRequest, user.getUsername());

        // Verify response
        assertEquals("FAILED", response.paymentStatus());
        assertEquals("PENDING_PAYMENT", response.bookingStatus());
        assertTrue(response.message().contains("Payment failed"));

        // Booking remains in PENDING_PAYMENT so user can re-try before hold expires
        Booking booking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("PENDING_PAYMENT", booking.getBookingStatus());
        assertEquals("FAILED", booking.getPaymentStatus());

        // SeatAllocation remains in HOLD (does NOT transition to BOOKED)
        Long allocationId = booking.getSeatAllocation().getSeatAllocationId();
        SeatAllocation allocation = allocationRepository.findById(allocationId).orElseThrow();
        assertEquals("HOLD", allocation.getStatus());

        // Failed transaction is securely recorded in DB
        List<PaymentTransaction> txns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
        assertEquals(1, txns.size());
        assertEquals("FAILED", txns.get(0).getStatus());
        assertEquals("Simulated payment failure", txns.get(0).getFailureReason());
        assertEquals(Boolean.TRUE, txns.get(0).getSimulateFailure());
    }

    @Test
    void duplicatePayment_withSameIdempotencyKey_returnsIdempotentResponse() {
        User user = createUser("pay-idempotent");
        JourneySeatInventory seatInv = inventories.get(3);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        PaymentRequestDTO request = new PaymentRequestDTO("MOCK_UPI", "idem-unique-key", false);

        PaymentResponseDTO first = bookingService.processPayment(created.bookingRef(), request, user.getUsername());
        assertEquals("SUCCESS", first.paymentStatus());

        // Second call with same idempotency key
        PaymentResponseDTO second = bookingService.processPayment(created.bookingRef(), request, user.getUsername());
        assertEquals(first.transactionRef(), second.transactionRef());
        assertEquals("SUCCESS", second.paymentStatus());
        assertEquals("CONFIRMED", second.bookingStatus());

        // Exactly 1 transaction recorded in DB
        List<PaymentTransaction> txns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
        assertEquals(1, txns.size());
    }

    @Test
    void duplicatePayment_withoutIdempotencyKey_rejectsAlreadyConfirmed() {
        User user = createUser("pay-dup-conflict");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        bookingService.processPayment(created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", null, false), user.getUsername());

        // Second attempt to pay an already confirmed booking without matching key throws IllegalStateException (409 Conflict)
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                bookingService.processPayment(created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", null, false), user.getUsername()));

        assertTrue(ex.getMessage().contains("already been confirmed"));
    }

    @Test
    void idempotencyKey_parameterMismatch_rejectsWithConflict409() {
        User user = createUser("pay-mismatch");
        JourneySeatInventory seatInv = inventories.get(1);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        PaymentRequestDTO initialRequest = new PaymentRequestDTO("MOCK_UPI", "key-mismatch-test", false);
        PaymentResponseDTO initialResponse = bookingService.processPayment(
                created.bookingRef(), initialRequest, user.getUsername());
        assertEquals("SUCCESS", initialResponse.paymentStatus());

        // Same key, different paymentMethod
        PaymentRequestDTO conflictingMethod = new PaymentRequestDTO("MOCK_CARD", "key-mismatch-test", false);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                bookingService.processPayment(created.bookingRef(), conflictingMethod, user.getUsername()));
        assertTrue(ex.getMessage().contains("previously used with different request parameters"));

        // Same key, different simulateFailure
        PaymentRequestDTO conflictingFailure = new PaymentRequestDTO("MOCK_UPI", "key-mismatch-test", true);
        IllegalStateException ex2 = assertThrows(IllegalStateException.class, () ->
                bookingService.processPayment(created.bookingRef(), conflictingFailure, user.getUsername()));
        assertTrue(ex2.getMessage().contains("previously used with different request parameters"));
    }

    @Test
    void failedPayment_canBeSafelyRetriedWithNewKeyAndConfirmed() {
        User user = createUser("pay-retry");
        JourneySeatInventory seatInv = inventories.get(2);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        // Attempt 1: Failed payment
        PaymentRequestDTO failedRequest = new PaymentRequestDTO("MOCK_UPI", "key-attempt-1", true);
        PaymentResponseDTO failedResponse = bookingService.processPayment(
                created.bookingRef(), failedRequest, user.getUsername());
        assertEquals("FAILED", failedResponse.paymentStatus());
        assertEquals("PENDING_PAYMENT", failedResponse.bookingStatus());

        // Booking remains PENDING_PAYMENT, seat hold remains HOLD
        Booking bookingAfterFail = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("PENDING_PAYMENT", bookingAfterFail.getBookingStatus());
        assertEquals("FAILED", bookingAfterFail.getPaymentStatus());

        // Attempt 2: Successful retry with a new idempotency key
        PaymentRequestDTO retryRequest = new PaymentRequestDTO("MOCK_UPI", "key-attempt-2", false);
        PaymentResponseDTO retryResponse = bookingService.processPayment(
                created.bookingRef(), retryRequest, user.getUsername());
        assertEquals("SUCCESS", retryResponse.paymentStatus());
        assertEquals("CONFIRMED", retryResponse.bookingStatus());

        // Both attempts preserved in audit trail
        List<PaymentTransaction> txns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
        assertEquals(2, txns.size());
        assertEquals("FAILED", txns.get(0).getStatus());
        assertEquals("SUCCESS", txns.get(1).getStatus());

        Booking confirmedBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("CONFIRMED", confirmedBooking.getBookingStatus());
        assertEquals("SUCCESS", confirmedBooking.getPaymentStatus());

        SeatAllocation allocation = allocationRepository.findById(
                confirmedBooking.getSeatAllocation().getSeatAllocationId()).orElseThrow();
        assertEquals("BOOKED", allocation.getStatus());
    }

    @Test
    void concurrentPaymentAttempts_withSameIdempotencyKey_onlyOneExecutesOtherReturnsIdempotentResult() throws Exception {
        User user = createUser("pay-same-key");
        JourneySeatInventory seatInv = inventories.get(3);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        String sharedKey = "shared-idemp-key-" + UUID.randomUUID();
        CountDownLatch startSignal = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<PaymentResponseDTO> responses = Collections.synchronizedList(new ArrayList<>());
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        try {
            Future<?> f1 = executor.submit(() -> {
                try {
                    startSignal.await();
                    responses.add(bookingService.processPayment(
                            created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", sharedKey, false), user.getUsername()));
                } catch (Exception e) {
                    errors.add(e);
                }
            });

            Future<?> f2 = executor.submit(() -> {
                try {
                    startSignal.await();
                    responses.add(bookingService.processPayment(
                            created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", sharedKey, false), user.getUsername()));
                } catch (Exception e) {
                    errors.add(e);
                }
            });

            startSignal.countDown();
            f1.get();
            f2.get();

            assertTrue(errors.isEmpty(), "Neither concurrent call with the same valid idempotency key should error");
            assertEquals(2, responses.size());
            assertEquals(responses.get(0).transactionRef(), responses.get(1).transactionRef(),
                    "Both responses must return the exact same transaction reference");
            assertEquals("SUCCESS", responses.get(0).paymentStatus());
            assertEquals("SUCCESS", responses.get(1).paymentStatus());

            // Exactly 1 database transaction record
            List<PaymentTransaction> txns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
            assertEquals(1, txns.size(), "Database must contain exactly 1 transaction record despite concurrent submission");

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentPaymentAttempts_onlyOneSucceedsAndPreventsDoubleBooking() throws Exception {
        User user = createUser("pay-concurrent");
        JourneySeatInventory seatInv = inventories.get(1);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        CountDownLatch startSignal = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<String> outcomes = Collections.synchronizedList(new ArrayList<>());

        try {
            Future<?> f1 = executor.submit(() -> {
                try {
                    startSignal.await();
                    PaymentResponseDTO resp = bookingService.processPayment(
                            created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", null, false), user.getUsername());
                    outcomes.add(resp.bookingStatus());
                } catch (Exception e) {
                    outcomes.add(e.getClass().getSimpleName());
                }
            });

            Future<?> f2 = executor.submit(() -> {
                try {
                    startSignal.await();
                    PaymentResponseDTO resp = bookingService.processPayment(
                            created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", null, false), user.getUsername());
                    outcomes.add(resp.bookingStatus());
                } catch (Exception e) {
                    outcomes.add(e.getClass().getSimpleName());
                }
            });

            startSignal.countDown();
            f1.get();
            f2.get();

            // Exactly one call must have CONFIRMED the booking, and the other must have encountered IllegalStateException (409)
            assertEquals(2, outcomes.size());
            assertTrue(outcomes.contains("CONFIRMED"), "One thread must succeed in confirming the booking");
            assertTrue(outcomes.contains("IllegalStateException"), "Second thread must receive IllegalStateException conflict");

            // Database integrity checks
            Booking booking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
            assertEquals("CONFIRMED", booking.getBookingStatus());
            assertEquals("SUCCESS", booking.getPaymentStatus());

            Long allocationId = booking.getSeatAllocation().getSeatAllocationId();
            SeatAllocation allocation = allocationRepository.findById(allocationId).orElseThrow();
            assertEquals("BOOKED", allocation.getStatus());

            List<PaymentTransaction> txns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
            assertEquals(1, txns.size(), "Exactly one payment transaction must be persisted");
            assertEquals("SUCCESS", txns.get(0).getStatus());

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSeatReleaseAndPayment_serializedSafelyWithoutOverwrite() throws Exception {
        User user = createUser("pay-vs-release");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        CountDownLatch startSignal = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<String> outcomes = Collections.synchronizedList(new ArrayList<>());

        try {
            // Thread 1: Admin releaseSeat
            Future<?> f1 = executor.submit(() -> {
                try {
                    startSignal.await();
                    seatInventoryService.releaseSeat(journeyId, seatInv.getSeat().getSeatId());
                    outcomes.add("RELEASE_SUCCESS");
                } catch (Exception e) {
                    outcomes.add("RELEASE_" + e.getClass().getSimpleName());
                }
            });

            // Thread 2: User processPayment
            Future<?> f2 = executor.submit(() -> {
                try {
                    startSignal.await();
                    PaymentResponseDTO resp = bookingService.processPayment(
                            created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", null, false), user.getUsername());
                    outcomes.add("PAY_" + resp.bookingStatus());
                } catch (Exception e) {
                    outcomes.add("PAY_" + e.getClass().getSimpleName());
                }
            });

            startSignal.countDown();
            f1.get();
            f2.get();

            assertEquals(2, outcomes.size());
            assertTrue(outcomes.contains("RELEASE_SUCCESS"), "Seat release should execute cleanly");

            Booking booking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
            SeatAllocation allocation = allocationRepository.findById(
                    booking.getSeatAllocation().getSeatAllocationId()).orElseThrow();

            // Either payment won the race (BOOKED) or release won the race (RELEASED), never a corrupted hybrid state
            if ("CONFIRMED".equals(booking.getBookingStatus())) {
                assertEquals("BOOKED", allocation.getStatus());
            } else {
                assertEquals("RELEASED", allocation.getStatus());
                assertNotEquals("CONFIRMED", booking.getBookingStatus());
            }

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void paymentAuthorization_userCannotPayForAnotherUsersBooking() {
        User owner = createUser("owner-user");
        User attacker = createUser("attacker-user");
        JourneySeatInventory seatInv = inventories.get(2);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, owner, 10);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                bookingService.processPayment(created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", null, false), attacker.getUsername()));

        assertTrue(ex.getMessage().contains("not authorized to pay"));
    }

    @Test
    void paymentAmount_isDerivedServerSideAndNeverTrustsClient() {
        User user = createUser("fare-check");
        JourneySeatInventory seatInv = inventories.get(3);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        // Before payment, booking amount was null (no fare model yet)
        Booking beforePay = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertNull(beforePay.getAmount());

        // Process payment
        PaymentResponseDTO response = bookingService.processPayment(
                created.bookingRef(), new PaymentRequestDTO("MOCK_UPI", null, false), user.getUsername());

        // Server-side deterministic default mock fare was applied
        assertEquals(new BigDecimal("500.00"), response.amount());

        Booking afterPay = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals(new BigDecimal("500.00"), afterPay.getAmount());
    }

    @Test
    void databaseConstraint_duplicateBookingAndIdempotencyKey_throwsDataIntegrityViolationException() {
        User user = createUser("db-constraint");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);
        Booking booking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();

        PaymentTransaction txn1 = new PaymentTransaction();
        txn1.setBooking(booking);
        txn1.setBookingRef(booking.getBookingRef());
        txn1.setTransactionRef("TXN-DB-TEST-1");
        txn1.setPaymentMethod("MOCK_UPI");
        txn1.setAmount(new BigDecimal("500.00"));
        txn1.setStatus("SUCCESS");
        txn1.setIdempotencyKey("dup-db-key");
        txn1.setCreatedAt(LocalDateTime.now());
        paymentTransactionRepository.saveAndFlush(txn1);

        PaymentTransaction txn2 = new PaymentTransaction();
        txn2.setBooking(booking);
        txn2.setBookingRef(booking.getBookingRef());
        txn2.setTransactionRef("TXN-DB-TEST-2");
        txn2.setPaymentMethod("MOCK_UPI");
        txn2.setAmount(new BigDecimal("500.00"));
        txn2.setStatus("SUCCESS");
        txn2.setIdempotencyKey("dup-db-key");
        txn2.setCreatedAt(LocalDateTime.now());

        assertThrows(DataIntegrityViolationException.class, () ->
                paymentTransactionRepository.saveAndFlush(txn2));
    }

    @Test
    void databaseConstraint_multipleNullIdempotencyKeys_permittedWithoutConflict() {
        User user = createUser("db-null-keys");
        JourneySeatInventory seatInv = inventories.get(1);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);
        Booking booking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();

        PaymentTransaction txn1 = new PaymentTransaction();
        txn1.setBooking(booking);
        txn1.setBookingRef(booking.getBookingRef());
        txn1.setTransactionRef("TXN-NULL-TEST-1");
        txn1.setPaymentMethod("MOCK_UPI");
        txn1.setAmount(new BigDecimal("500.00"));
        txn1.setStatus("FAILED");
        txn1.setIdempotencyKey(null);
        txn1.setCreatedAt(LocalDateTime.now());
        assertDoesNotThrow(() -> paymentTransactionRepository.saveAndFlush(txn1));

        PaymentTransaction txn2 = new PaymentTransaction();
        txn2.setBooking(booking);
        txn2.setBookingRef(booking.getBookingRef());
        txn2.setTransactionRef("TXN-NULL-TEST-2");
        txn2.setPaymentMethod("MOCK_UPI");
        txn2.setAmount(new BigDecimal("500.00"));
        txn2.setStatus("SUCCESS");
        txn2.setIdempotencyKey(null);
        txn2.setCreatedAt(LocalDateTime.now());
        assertDoesNotThrow(() -> paymentTransactionRepository.saveAndFlush(txn2));
    }

    private Station station(String code, String name) {
        Station s = new Station();
        s.setStationCode(code);
        s.setStationName(name);
        s.setCity(name);
        s.setState("Payment State");
        return s;
    }

    private User createUser(String prefix) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername(username);
        user.setFullName(prefix);
        user.setEmail(username + "@payment-test.com");
        user.setPassword("not-used-password");
        user.setStatus("ACTIVE");
        user.setRole("ROLE_USER");
        return userRepository.save(user);
    }
}
