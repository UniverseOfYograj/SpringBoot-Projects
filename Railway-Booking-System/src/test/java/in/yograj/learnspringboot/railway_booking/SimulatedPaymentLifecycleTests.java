package in.yograj.learnspringboot.railway_booking;

import in.yograj.learnspringboot.railway_booking.dto.BookingDetailResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.BookingResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.CreateBookingRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.CreateTrainRouteRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.JourneyRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.SimulatedPaymentSummaryDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainStopRequestDTO;
import in.yograj.learnspringboot.railway_booking.entity.Booking;
import in.yograj.learnspringboot.railway_booking.entity.JourneySeatInventory;
import in.yograj.learnspringboot.railway_booking.entity.JourneyStop;
import in.yograj.learnspringboot.railway_booking.entity.PaymentTransaction;
import in.yograj.learnspringboot.railway_booking.entity.SeatAllocation;
import in.yograj.learnspringboot.railway_booking.entity.Station;
import in.yograj.learnspringboot.railway_booking.entity.Train;
import in.yograj.learnspringboot.railway_booking.entity.User;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.SeatHoldExpiredException;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.BookingRepository;
import in.yograj.learnspringboot.railway_booking.repository.JourneySeatInventoryRepository;
import in.yograj.learnspringboot.railway_booking.repository.JourneyStopRepository;
import in.yograj.learnspringboot.railway_booking.repository.PaymentTransactionRepository;
import in.yograj.learnspringboot.railway_booking.repository.SeatAllocationRepository;
import in.yograj.learnspringboot.railway_booking.repository.StationRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainRepository;
import in.yograj.learnspringboot.railway_booking.repository.UserRepository;
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

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:simulated-payment-lifecycle-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length",
        "railway.payment.mock-enabled=true",
        "railway.payment.simulated-enabled=true"
})
class SimulatedPaymentLifecycleTests {

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
                        throw new IllegalStateException("FATAL SAFETY VIOLATION: Test process connected to protected database 'railway_booking'!");
                    }
                }
            }
        }
    }

    @BeforeEach
    void setupJourneyAndInventory() {
        bookingService.setSimulatedPaymentEnabled(true);
        bookingService.setMockPaymentEnabled(true);

        Train train = new Train();
        train.setTrainNumber("SIM-" + UUID.randomUUID().toString().substring(0, 8));
        train.setTrainName("Simulated Express");
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
    void simulatedPaymentSummary_returnsPersistedFareAndHoldExpiration() {
        User user = createUser("sim-summary");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        SimulatedPaymentSummaryDTO summary = bookingService.getSimulatedPaymentSummary(created.bookingRef(), user.getUsername());

        assertNotNull(summary);
        assertEquals(created.bookingRef(), summary.bookingRef());
        assertEquals("PENDING_PAYMENT", summary.bookingStatus());
        assertEquals("PENDING", summary.paymentStatus());
        assertEquals(new BigDecimal("500.00"), summary.amount());
        assertEquals("SIMULATED", summary.mode());
        assertNotNull(summary.holdExpiresAt());
        assertTrue(summary.holdExpiresAt().isAfter(LocalDateTime.now()));
    }

    @Test
    void successfulSimulatedPayment_confirmsBookingAndBooksSeatAllocation() {
        User user = createUser("sim-success");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        String idempKey = "sim-idemp-" + UUID.randomUUID();
        PaymentResponseDTO response = bookingService.processSimulatedPaymentSuccess(
                created.bookingRef(), idempKey, user.getUsername());

        // Verify response
        assertNotNull(response.transactionRef());
        assertTrue(response.transactionRef().startsWith("SIM-"));
        assertEquals(created.bookingRef(), response.bookingRef());
        assertEquals("SUCCESS", response.paymentStatus());
        assertEquals("CONFIRMED", response.bookingStatus());
        assertEquals("SIMULATED", response.paymentMethod());
        assertEquals(new BigDecimal("500.00"), response.amount());

        // Verify database state
        Booking updatedBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("CONFIRMED", updatedBooking.getBookingStatus());
        assertEquals("SUCCESS", updatedBooking.getPaymentStatus());

        SeatAllocation allocation = allocationRepository.findById(updatedBooking.getSeatAllocation().getSeatAllocationId()).orElseThrow();
        assertEquals("BOOKED", allocation.getStatus());

        List<PaymentTransaction> txns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
        assertEquals(1, txns.size());
        PaymentTransaction txn = txns.get(0);
        assertEquals("SUCCESS", txn.getStatus());
        assertEquals("SIMULATED", txn.getPaymentMethod());
        assertEquals(idempKey, txn.getIdempotencyKey());
        assertEquals(false, txn.getSimulateFailure());
        assertNull(txn.getFailureReason());
    }

    @Test
    void failedSimulatedPayment_recordsFailedTransactionAndKeepsBookingPending() {
        User user = createUser("sim-fail");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        String idempKey = "sim-fail-idemp-" + UUID.randomUUID();
        PaymentResponseDTO response = bookingService.processSimulatedPaymentFailure(
                created.bookingRef(), idempKey, "Simulated payment failure", user.getUsername());

        // Verify response
        assertNotNull(response.transactionRef());
        assertTrue(response.transactionRef().startsWith("SIM-"));
        assertEquals("FAILED", response.paymentStatus());
        assertEquals("PENDING_PAYMENT", response.bookingStatus());
        assertEquals("SIMULATED", response.paymentMethod());

        // Verify database state
        Booking booking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("PENDING_PAYMENT", booking.getBookingStatus(), "Booking must remain PENDING_PAYMENT to allow retry");
        assertEquals("FAILED", booking.getPaymentStatus());

        SeatAllocation allocation = allocationRepository.findById(booking.getSeatAllocation().getSeatAllocationId()).orElseThrow();
        assertEquals("HOLD", allocation.getStatus(), "Seat hold must be preserved for retry");

        List<PaymentTransaction> txns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
        assertEquals(1, txns.size());
        assertEquals("FAILED", txns.get(0).getStatus());
        assertEquals("SIMULATED", txns.get(0).getPaymentMethod());
        assertEquals(true, txns.get(0).getSimulateFailure());
        assertEquals("Simulated payment failure", txns.get(0).getFailureReason());
    }

    @Test
    void retryAfterFailure_withNewIdempotencyKey_succeedsAndConfirmsBooking() {
        User user = createUser("sim-retry");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        String failKey = "sim-retry-fail-" + UUID.randomUUID();
        PaymentResponseDTO failedResponse = bookingService.processSimulatedPaymentFailure(
                created.bookingRef(), failKey, "Declined by simulator", user.getUsername());
        assertEquals("FAILED", failedResponse.paymentStatus());
        assertEquals("PENDING_PAYMENT", failedResponse.bookingStatus());

        // Retry with a NEW idempotency key
        String retryKey = "sim-retry-success-" + UUID.randomUUID();
        PaymentResponseDTO retryResponse = bookingService.processSimulatedPaymentSuccess(
                created.bookingRef(), retryKey, user.getUsername());
        assertEquals("SUCCESS", retryResponse.paymentStatus());
        assertEquals("CONFIRMED", retryResponse.bookingStatus());

        // Verify ledger has both transactions
        List<PaymentTransaction> txns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
        assertEquals(2, txns.size());
        assertTrue(txns.stream().anyMatch(t -> "FAILED".equals(t.getStatus()) && failKey.equals(t.getIdempotencyKey())));
        assertTrue(txns.stream().anyMatch(t -> "SUCCESS".equals(t.getStatus()) && retryKey.equals(t.getIdempotencyKey())));

        // Final booking status is confirmed
        Booking booking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("CONFIRMED", booking.getBookingStatus());
        assertEquals("SUCCESS", booking.getPaymentStatus());
        assertEquals("BOOKED", booking.getSeatAllocation().getStatus());
    }

    @Test
    void duplicateSuccessRequest_withSameIdempotencyKey_returnsIdempotentResponseWithoutDuplicateTxn() {
        User user = createUser("sim-idemp-succ");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        String idempKey = "sim-idemp-exact-" + UUID.randomUUID();
        PaymentResponseDTO first = bookingService.processSimulatedPaymentSuccess(
                created.bookingRef(), idempKey, user.getUsername());
        PaymentResponseDTO second = bookingService.processSimulatedPaymentSuccess(
                created.bookingRef(), idempKey, user.getUsername());

        assertEquals(first.transactionRef(), second.transactionRef());
        assertEquals("SUCCESS", second.paymentStatus());
        assertEquals("CONFIRMED", second.bookingStatus());
        assertTrue(second.message().contains("Idempotent response"));

        List<PaymentTransaction> txns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
        assertEquals(1, txns.size(), "Duplicate request with same key must not create a duplicate transaction record");
    }

    @Test
    void duplicateFailureRequest_withSameIdempotencyKey_returnsSameFailedTransaction() {
        User user = createUser("sim-idemp-fail");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        String idempKey = "sim-fail-exact-" + UUID.randomUUID();
        PaymentResponseDTO first = bookingService.processSimulatedPaymentFailure(
                created.bookingRef(), idempKey, "Failure reason", user.getUsername());
        PaymentResponseDTO second = bookingService.processSimulatedPaymentFailure(
                created.bookingRef(), idempKey, "Failure reason", user.getUsername());

        assertEquals(first.transactionRef(), second.transactionRef());
        assertEquals("FAILED", second.paymentStatus());
        assertTrue(second.message().contains("Idempotent response"));

        List<PaymentTransaction> txns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
        assertEquals(1, txns.size(), "Duplicate failed request with same key must not create duplicate transaction record");
    }

    @Test
    void idempotencyKeyMismatch_failsWhenReusedAcrossOutcomes() {
        User user = createUser("sim-mismatch");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        String idempKey = "sim-reused-key-" + UUID.randomUUID();
        bookingService.processSimulatedPaymentFailure(created.bookingRef(), idempKey, "Fail", user.getUsername());

        // Reusing the same key for a SUCCESS call must be rejected
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                bookingService.processSimulatedPaymentSuccess(created.bookingRef(), idempKey, user.getUsername()));
        assertTrue(ex.getMessage().contains("previously used for a failed payment attempt"));
    }

    @Test
    void paymentAttemptBelongingToAnotherUser_throwsAccessDeniedException() {
        User owner = createUser("sim-owner");
        User attacker = createUser("sim-attacker");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, owner, 10);

        assertThrows(AccessDeniedException.class, () ->
                bookingService.processSimulatedPaymentSuccess(created.bookingRef(), "any-key", attacker.getUsername()));
        assertThrows(AccessDeniedException.class, () ->
                bookingService.processSimulatedPaymentFailure(created.bookingRef(), "any-key", "reason", attacker.getUsername()));
        assertThrows(AccessDeniedException.class, () ->
                bookingService.getSimulatedPaymentSummary(created.bookingRef(), attacker.getUsername()));
    }

    @Test
    void alreadyConfirmedBooking_rejectsNewPaymentAttempt() {
        User user = createUser("sim-already-confirmed");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        bookingService.processSimulatedPaymentSuccess(created.bookingRef(), "key-1", user.getUsername());

        // Attempting to pay again with a new key must throw IllegalStateException
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                bookingService.processSimulatedPaymentSuccess(created.bookingRef(), "key-2", user.getUsername()));
        assertTrue(ex.getMessage().contains("already been confirmed"));
    }

    @Test
    void expiredSeatHold_rejectsPaymentAndMarksBookingExpired() {
        User user = createUser("sim-expired");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        // Manually expire the hold in the past
        Booking booking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        SeatAllocation allocation = booking.getSeatAllocation();
        allocation.setHoldExpiresAt(LocalDateTime.now().minusMinutes(5));
        allocationRepository.save(allocation);

        assertThrows(SeatHoldExpiredException.class, () ->
                bookingService.processSimulatedPaymentSuccess(created.bookingRef(), "key-exp", user.getUsername()));

        Booking expiredBooking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals("EXPIRED", expiredBooking.getBookingStatus());
        assertEquals("EXPIRED", expiredBooking.getPaymentStatus());

        SeatAllocation expiredAlloc = allocationRepository.findById(allocation.getSeatAllocationId()).orElseThrow();
        assertEquals("EXPIRED", expiredAlloc.getStatus());
    }

    @Test
    void amountTakenFromPersistedBookingFare_strictlyEnforced() {
        User user = createUser("sim-fare");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        // Manually set custom persisted fare on booking
        Booking booking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        BigDecimal expectedFare = new BigDecimal("850.50");
        booking.setAmount(expectedFare);
        bookingRepository.save(booking);

        PaymentResponseDTO response = bookingService.processSimulatedPaymentSuccess(
                created.bookingRef(), "key-fare", user.getUsername());

        assertEquals(expectedFare, response.amount());
        PaymentTransaction txn = paymentTransactionRepository.findByBookingRef(created.bookingRef()).get(0);
        assertEquals(expectedFare, txn.getAmount());
    }

    @Test
    void cancellationAfterSimulatedSuccess_processesRefundAndReleasesSeat() {
        User user = createUser("sim-cancel");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        bookingService.processSimulatedPaymentSuccess(created.bookingRef(), "pay-key", user.getUsername());

        BookingDetailResponseDTO cancelled = bookingService.cancelBooking(created.bookingRef(), user.getUsername());
        assertEquals("CANCELLED", cancelled.bookingStatus());
        assertEquals("REFUNDED", cancelled.paymentStatus());

        // Verify refund transaction in ledger
        List<PaymentTransaction> txns = paymentTransactionRepository.findByBookingRef(created.bookingRef());
        assertEquals(2, txns.size());
        PaymentTransaction refundTxn = txns.stream()
                .filter(t -> "REFUNDED".equals(t.getStatus()))
                .findFirst()
                .orElseThrow();
        assertEquals("SIMULATED", refundTxn.getPaymentMethod());
        assertEquals(new BigDecimal("500.00"), refundTxn.getAmount());

        // Seat allocation must be released/CANCELLED
        SeatAllocation alloc = allocationRepository.findById(cancelled.seatAllocationId()).orElseThrow();
        assertEquals("CANCELLED", alloc.getStatus());
    }

    @Test
    void simulatedPaymentDisabled_rejectsOperationWithIllegalStateException() {
        User user = createUser("sim-disabled");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        bookingService.setSimulatedPaymentEnabled(false);

        IllegalStateException ex1 = assertThrows(IllegalStateException.class, () ->
                bookingService.processSimulatedPaymentSuccess(created.bookingRef(), "key", user.getUsername()));
        assertTrue(ex1.getMessage().contains("Simulated payments are disabled"));

        IllegalStateException ex2 = assertThrows(IllegalStateException.class, () ->
                bookingService.processSimulatedPaymentFailure(created.bookingRef(), "key", "reason", user.getUsername()));
        assertTrue(ex2.getMessage().contains("Simulated payments are disabled"));

        IllegalStateException ex3 = assertThrows(IllegalStateException.class, () ->
                bookingService.getSimulatedPaymentSummary(created.bookingRef(), user.getUsername()));
        assertTrue(ex3.getMessage().contains("Simulated payments are disabled"));
    }

    @Test
    void genericPayEndpoint_withSimulatedMethod_processesSuccessfully() {
        User user = createUser("sim-generic");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        PaymentRequestDTO request = new PaymentRequestDTO("SIMULATED", "gen-sim-key", false);
        PaymentResponseDTO response = bookingService.processPayment(created.bookingRef(), request, user.getUsername());

        assertEquals("SUCCESS", response.paymentStatus());
        assertEquals("CONFIRMED", response.bookingStatus());
        assertEquals("SIMULATED", response.paymentMethod());
    }

    @Test
    void missingIdempotencyKey_isRejectedWithBadRequest() {
        User user = createUser("sim-nokey");
        JourneySeatInventory seatInv = inventories.get(0);
        BookingResponseDTO created = createPendingBookingForSeat(seatInv, user, 10);

        assertThrows(BadRequestException.class, () ->
                bookingService.processSimulatedPaymentSuccess(created.bookingRef(), null, user.getUsername()));
        assertThrows(BadRequestException.class, () ->
                bookingService.processSimulatedPaymentSuccess(created.bookingRef(), "", user.getUsername()));
        assertThrows(BadRequestException.class, () ->
                bookingService.processSimulatedPaymentFailure(created.bookingRef(), null, "reason", user.getUsername()));
    }

    private Station station(String code, String name) {
        Station s = new Station();
        s.setStationCode(code);
        s.setStationName(name);
        s.setCity(name);
        s.setState("Sim State");
        return s;
    }

    private User createUser(String prefix) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername(username);
        user.setFullName(prefix);
        user.setEmail(username + "@sim-test.com");
        user.setPassword("password-not-used");
        user.setStatus("ACTIVE");
        user.setRole("ROLE_USER");
        return userRepository.save(user);
    }
}
