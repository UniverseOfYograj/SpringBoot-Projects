package in.yograj.learnspringboot.railway_booking;

import in.yograj.learnspringboot.railway_booking.dto.BookingResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.CreateBookingRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.CreateTrainRouteRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.JourneyRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainStopRequestDTO;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.outbox.IdempotentEventConsumerService;
import in.yograj.learnspringboot.railway_booking.outbox.OutboxEventRelay;
import in.yograj.learnspringboot.railway_booking.outbox.OutboxEventService;
import in.yograj.learnspringboot.railway_booking.outbox.OutboxMessagePublisher;
import in.yograj.learnspringboot.railway_booking.repository.*;
import in.yograj.learnspringboot.railway_booking.service.BookingService;
import in.yograj.learnspringboot.railway_booking.service.JourneyService;
import in.yograj.learnspringboot.railway_booking.service.SeatInventoryService;
import in.yograj.learnspringboot.railway_booking.service.TrainRouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:transactional-outbox-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "railway.outbox.kafka.enabled=false",
        "railway.cache.enabled=false",
        "tatkal.queue.enabled=false",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length"
})
class TransactionalOutboxLifecycleTests {

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    @MockitoBean
    private OutboxMessagePublisher outboxMessagePublisher;

    @Autowired private TrainRepository trainRepository;
    @Autowired private StationRepository stationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JourneySeatInventoryRepository inventoryRepository;
    @Autowired private JourneyStopRepository journeyStopRepository;
    @Autowired private SeatAllocationRepository allocationRepository;
    @Autowired private BookingRepository bookingRepository;
    @MockitoSpyBean private PaymentTransactionRepository paymentTransactionRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private ProcessedConsumerEventRepository processedConsumerEventRepository;

    @Autowired private TrainRouteService trainRouteService;
    @Autowired private JourneyService journeyService;
    @Autowired private SeatInventoryService seatInventoryService;
    @Autowired private BookingService bookingService;
    @Autowired private OutboxEventService outboxEventService;
    @Autowired private OutboxEventRelay outboxEventRelay;
    @Autowired private IdempotentEventConsumerService idempotentConsumerService;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long journeyId;
    private List<JourneyStop> runStops;
    private List<JourneySeatInventory> inventories;

    @BeforeEach
    void setup() {
        outboxEventRepository.deleteAll();
        processedConsumerEventRepository.deleteAll();
        paymentTransactionRepository.deleteAll();
        bookingRepository.deleteAll();
        allocationRepository.deleteAll();

        Train train = new Train();
        train.setTrainNumber("OUTBOX-" + UUID.randomUUID().toString().substring(0, 8));
        train.setTrainName("Outbox Express");
        train.setStatus("ACTIVE");
        train = trainRepository.save(train);

        List<Station> stations = List.of(
                station("OST1-" + UUID.randomUUID().toString().substring(0, 4), "Outbox Station 1"),
                station("OST2-" + UUID.randomUUID().toString().substring(0, 4), "Outbox Station 2"),
                station("OST3-" + UUID.randomUUID().toString().substring(0, 4), "Outbox Station 3")
        );
        stationRepository.saveAll(stations);

        var route = trainRouteService.createRoute(train.getTrainId(),
                new CreateTrainRouteRequestDTO("route-outbox-" + UUID.randomUUID(),
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
        this.inventories = inventoryRepository.findByJourney_JourneyId(journeyId);
    }

    private Station station(String code, String name) {
        Station s = new Station();
        s.setStationCode(code);
        s.setStationName(name);
        s.setCity("Test City");
        s.setState("Test State");
        return s;
    }

    private User createUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@outbox-test.com");
        u.setPassword("hashed-password");
        u.setFullName("Outbox User");
        u.setRole("ROLE_USER");
        u.setStatus("ACTIVE");
        return userRepository.save(u);
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
    void processPayment_success_atomicallyPersistsBookingConfirmedOutboxEvent() {
        User user = createUser("outbox-conf-" + UUID.randomUUID().toString().substring(0, 6));
        BookingResponseDTO created = createPendingBookingForSeat(inventories.get(0), user, 10);

        PaymentResponseDTO payment = bookingService.processPayment(
                created.bookingRef(),
                new PaymentRequestDTO("MOCK_UPI", "IDEMP-" + UUID.randomUUID(), false),
                user.getUsername()
        );

        assertEquals("SUCCESS", payment.paymentStatus());
        assertEquals("CONFIRMED", payment.bookingStatus());

        List<OutboxEvent> events = outboxEventRepository.findByAggregateIdOrderByCreatedAtAsc(created.bookingRef());
        assertEquals(1, events.size(), "Exactly one outbox event must be persisted for confirmed booking");

        OutboxEvent event = events.get(0);
        assertEquals("BOOKING", event.getAggregateType());
        assertEquals(created.bookingRef(), event.getAggregateId());
        assertEquals("BOOKING_CONFIRMED", event.getEventType());
        assertEquals(OutboxEventStatus.PENDING, event.getStatus());
        assertEquals(0, event.getRetryCount());
        assertNull(event.getPublishedAt());
        assertTrue(event.getPayload().contains(created.bookingRef()));
        assertTrue(event.getPayload().contains("CONFIRMED"));
    }

    @Test
    void processPayment_simulatedFailure_atomicallyPersistsPaymentFailedOutboxEvent() {
        User user = createUser("outbox-fail-" + UUID.randomUUID().toString().substring(0, 6));
        BookingResponseDTO created = createPendingBookingForSeat(inventories.get(1), user, 10);

        PaymentResponseDTO payment = bookingService.processPayment(
                created.bookingRef(),
                new PaymentRequestDTO("MOCK_UPI", "IDEMP-" + UUID.randomUUID(), true),
                user.getUsername()
        );

        assertEquals("FAILED", payment.paymentStatus());

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertEquals(1, events.size());

        OutboxEvent event = events.get(0);
        assertEquals("PAYMENT", event.getAggregateType());
        assertEquals("PAYMENT_FAILED", event.getEventType());
        assertEquals(OutboxEventStatus.PENDING, event.getStatus());
        assertTrue(event.getPayload().contains("Simulated payment failure"));
    }

    @Test
    void cancelBooking_success_atomicallyPersistsBookingCancelledOutboxEvent() {
        User user = createUser("outbox-canc-" + UUID.randomUUID().toString().substring(0, 6));
        BookingResponseDTO created = createPendingBookingForSeat(inventories.get(2), user, 10);

        bookingService.processPayment(
                created.bookingRef(),
                new PaymentRequestDTO("MOCK_UPI", "IDEMP-" + UUID.randomUUID(), false),
                user.getUsername()
        );

        outboxEventRepository.deleteAll();

        bookingService.cancelBooking(created.bookingRef(), user.getUsername());

        List<OutboxEvent> events = outboxEventRepository.findByAggregateIdOrderByCreatedAtAsc(created.bookingRef());
        assertEquals(1, events.size(), "Exactly one BOOKING_CANCELLED outbox event must be persisted");

        OutboxEvent event = events.get(0);
        assertEquals("BOOKING", event.getAggregateType());
        assertEquals(created.bookingRef(), event.getAggregateId());
        assertEquals("BOOKING_CANCELLED", event.getEventType());
        assertEquals(OutboxEventStatus.PENDING, event.getStatus());
        assertTrue(event.getPayload().contains("CANCELLED"));
    }

    @Test
    void cancelBooking_failureDuringRefundLedgerSave_rollsBackOutboxEventAtomically() {
        User user = createUser("outbox-roll-" + UUID.randomUUID().toString().substring(0, 6));
        BookingResponseDTO created = createPendingBookingForSeat(inventories.get(3), user, 10);

        bookingService.processPayment(
                created.bookingRef(),
                new PaymentRequestDTO("MOCK_UPI", "IDEMP-" + UUID.randomUUID(), false),
                user.getUsername()
        );

        outboxEventRepository.deleteAll();

        doAnswer(invocation -> {
            PaymentTransaction txn = invocation.getArgument(0);
            if (txn.getTransactionRef() != null && txn.getTransactionRef().startsWith("TXN-REFUND-")) {
                throw new RuntimeException("Simulated refund ledger write failure for outbox rollback test");
            }
            return invocation.callRealMethod();
        }).when(paymentTransactionRepository).save(any(PaymentTransaction.class));

        assertThrows(RuntimeException.class, () ->
                bookingService.cancelBooking(created.bookingRef(), user.getUsername())
        );

        reset(paymentTransactionRepository);

        List<OutboxEvent> events = outboxEventRepository.findByAggregateIdOrderByCreatedAtAsc(created.bookingRef());
        assertTrue(events.isEmpty(), "Outbox event must be rolled back atomically when cancellation fails");
    }

    @Test
    void outboxRelay_success_dispatchesEventAndMarksPublished() throws Exception {
        doNothing().when(outboxMessagePublisher).publish(any(OutboxEvent.class));

        OutboxEvent event = transactionTemplate.execute(status ->
                outboxEventService.stageEvent("BOOKING", "RB-RELAY-001", "BOOKING_CONFIRMED",
                        java.util.Map.of("test", "value"))
        );
        assertNotNull(event);
        assertEquals(OutboxEventStatus.PENDING, event.getStatus());

        int published = outboxEventRelay.relayPendingEvents(10);
        assertEquals(1, published);

        OutboxEvent updated = outboxEventRepository.findById(event.getEventId()).orElseThrow();
        assertEquals(OutboxEventStatus.PUBLISHED, updated.getStatus());
        assertNotNull(updated.getPublishedAt());
        assertNull(updated.getLastError());

        verify(outboxMessagePublisher, times(1)).publish(any(OutboxEvent.class));
    }

    @Test
    void outboxRelay_twoWorkersClaimEventOnlyOnce() throws Exception {
        OutboxEvent event = transactionTemplate.execute(status ->
                outboxEventService.stageEvent("BOOKING", "RB-CLAIM-001", "BOOKING_CONFIRMED",
                        java.util.Map.of("test", "claim"))
        );
        assertNotNull(event);

        CountDownLatch publishStarted = new CountDownLatch(1);
        CountDownLatch allowPublishToFinish = new CountDownLatch(1);
        AtomicInteger publishCalls = new AtomicInteger();
        doAnswer(invocation -> {
            publishCalls.incrementAndGet();
            publishStarted.countDown();
            assertTrue(allowPublishToFinish.await(10, TimeUnit.SECONDS),
                    "Test must release the broker acknowledgement wait");
            return null;
        }).when(outboxMessagePublisher).publish(any(OutboxEvent.class));

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> firstWorker = workers.submit(() -> outboxEventRelay.relayPendingEvents(10));
            assertTrue(publishStarted.await(10, TimeUnit.SECONDS), "First worker must begin publishing");

            int secondWorkerPublished = outboxEventRelay.relayPendingEvents(10);
            allowPublishToFinish.countDown();

            assertEquals(0, secondWorkerPublished);
            assertEquals(1, firstWorker.get(10, TimeUnit.SECONDS));
            assertEquals(1, publishCalls.get());
            assertEquals(OutboxEventStatus.PUBLISHED,
                    outboxEventRepository.findById(event.getEventId()).orElseThrow().getStatus());
        } finally {
            allowPublishToFinish.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void outboxRelay_publicationFailure_marksFailedIncrementsRetryCountAndRecordsError() throws Exception {
        doThrow(new RuntimeException("Kafka broker unreachable")).when(outboxMessagePublisher).publish(any(OutboxEvent.class));

        OutboxEvent event = transactionTemplate.execute(status ->
                outboxEventService.stageEvent("BOOKING", "RB-RELAY-002", "BOOKING_CONFIRMED",
                        java.util.Map.of("test", "fail"))
        );
        assertNotNull(event);

        int published = outboxEventRelay.relayPendingEvents(10);
        assertEquals(0, published);

        OutboxEvent updated = outboxEventRepository.findById(event.getEventId()).orElseThrow();
        assertEquals(OutboxEventStatus.FAILED, updated.getStatus());
        assertEquals(1, updated.getRetryCount());
        assertEquals("Kafka broker unreachable", updated.getLastError());
        assertNull(updated.getPublishedAt());
    }

    @Test
    void outboxRelay_retriesAfterDelayAndMarksExhaustedEventTerminal() throws Exception {
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID().toString(),
                "BOOKING",
                "RB-RETRY-001",
                "BOOKING_CONFIRMED",
                "{}",
                LocalDateTime.now()
        );
        event.setMaxRetries(2);
        outboxEventRepository.saveAndFlush(event);
        doThrow(new RuntimeException("Temporary broker failure"))
                .when(outboxMessagePublisher).publish(any(OutboxEvent.class));

        assertEquals(0, outboxEventRelay.relayPendingEvents(10));
        OutboxEvent firstFailure = outboxEventRepository.findById(event.getEventId()).orElseThrow();
        assertEquals(OutboxEventStatus.FAILED, firstFailure.getStatus());
        assertEquals(1, firstFailure.getRetryCount());
        assertNotNull(firstFailure.getNextAttemptAt());
        assertTrue(firstFailure.getNextAttemptAt().isAfter(LocalDateTime.now()));

        assertEquals(0, outboxEventRelay.relayPendingEvents(10));
        verify(outboxMessagePublisher, times(1)).publish(any(OutboxEvent.class));

        firstFailure.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        outboxEventRepository.saveAndFlush(firstFailure);
        assertEquals(0, outboxEventRelay.relayPendingEvents(10));

        OutboxEvent terminalFailure = outboxEventRepository.findById(event.getEventId()).orElseThrow();
        assertEquals(OutboxEventStatus.DEAD, terminalFailure.getStatus());
        assertEquals(2, terminalFailure.getRetryCount());
        assertEquals("Temporary broker failure", terminalFailure.getLastError());
        assertNull(terminalFailure.getClaimToken());
        verify(outboxMessagePublisher, times(2)).publish(any(OutboxEvent.class));

        assertEquals(0, outboxEventRelay.relayPendingEvents(10));
        verifyNoMoreInteractions(outboxMessagePublisher);
    }

    @Test
    void outboxRelay_eventExceedingMaxRetries_isExcludedFromSubsequentRelayAttempts() {
        OutboxEvent poisonPill = new OutboxEvent(
                "poison-event-001",
                "BOOKING",
                "RB-POISON-001",
                "BOOKING_CONFIRMED",
                "{}",
                LocalDateTime.now().minusMinutes(5)
        );
        poisonPill.setStatus(OutboxEventStatus.FAILED);
        poisonPill.setRetryCount(3);
        poisonPill.setMaxRetries(3);
        poisonPill.setLastError("Repeated broker reject");
        outboxEventRepository.save(poisonPill);

        int published = outboxEventRelay.relayPendingEvents(10);
        assertEquals(0, published);

        verifyNoInteractions(outboxMessagePublisher);
    }

    @Test
    void idempotentConsumer_firstExecutionProcesses_duplicateDeliveryIsSafelyIgnored() {
        String eventId = UUID.randomUUID().toString();
        String consumerName = "sms-notification-service";
        AtomicInteger processCount = new AtomicInteger(0);

        boolean first = idempotentConsumerService.processIdempotently(
                eventId,
                consumerName,
                "BOOKING_CONFIRMED",
                id -> processCount.incrementAndGet()
        );

        assertTrue(first, "First event delivery must be processed");
        assertEquals(1, processCount.get());
        assertTrue(processedConsumerEventRepository.existsByEventIdAndConsumerName(eventId, consumerName));

        boolean duplicate = idempotentConsumerService.processIdempotently(
                eventId,
                consumerName,
                "BOOKING_CONFIRMED",
                id -> processCount.incrementAndGet()
        );

        assertFalse(duplicate, "Duplicate event delivery must be skipped");
        assertEquals(1, processCount.get(), "Handler must NOT be executed for duplicate event");
    }

    @Test
    void idempotentConsumer_handlerFailureRollsBackMarkerAndDatabaseSideEffects() {
        String eventId = UUID.randomUUID().toString();
        String consumerName = "database-side-effect-consumer";

        assertThrows(IllegalStateException.class, () -> idempotentConsumerService.processIdempotently(
                eventId,
                consumerName,
                "BOOKING_CONFIRMED",
                id -> {
                    processedConsumerEventRepository.saveAndFlush(new ProcessedConsumerEvent(
                            id,
                            "consumer-side-effect",
                            "BOOKING_CONFIRMED",
                            LocalDateTime.now()
                    ));
                    throw new IllegalStateException("Simulated database-local consumer failure");
                }
        ));

        assertFalse(processedConsumerEventRepository.existsByEventIdAndConsumerName(eventId, consumerName));
        assertFalse(processedConsumerEventRepository.existsByEventIdAndConsumerName(eventId, "consumer-side-effect"));
    }

    @Test
    void staleClaim_recoveryAfterTimeout_incrementsRetryAndPublishes() throws Exception {
        doNothing().when(outboxMessagePublisher).publish(any(OutboxEvent.class));

        OutboxEvent staleEvent = new OutboxEvent(
                "stale-event-001",
                "BOOKING",
                "RB-STALE-001",
                "BOOKING_CONFIRMED",
                "{}",
                LocalDateTime.now().minusMinutes(10)
        );
        staleEvent.setStatus(OutboxEventStatus.CLAIMED);
        staleEvent.setClaimToken("dead-worker-token");
        staleEvent.setClaimedAt(LocalDateTime.now().minusMinutes(5));
        staleEvent.setRetryCount(0);
        staleEvent.setMaxRetries(3);
        outboxEventRepository.saveAndFlush(staleEvent);

        outboxEventRelay.setClaimTimeoutMs(60000); // 1 minute timeout
        int published = outboxEventRelay.relayPendingEvents(10);
        assertEquals(1, published, "Stale claim must be reclaimed and published");

        OutboxEvent updated = outboxEventRepository.findById("stale-event-001").orElseThrow();
        assertEquals(OutboxEventStatus.PUBLISHED, updated.getStatus());
        assertEquals(1, updated.getRetryCount(), "Retry count must be incremented upon reclaiming stale lease");
        assertNotNull(updated.getPublishedAt());
        verify(outboxMessagePublisher, times(1)).publish(any(OutboxEvent.class));
    }

    @Test
    void staleClaim_repeatedCrashesExhaustRetries_marksDeadWithoutPublishing() throws Exception {
        OutboxEvent crashPoisonPill = new OutboxEvent(
                "crash-pill-001",
                "BOOKING",
                "RB-CRASH-001",
                "BOOKING_CONFIRMED",
                "{}",
                LocalDateTime.now().minusMinutes(10)
        );
        crashPoisonPill.setStatus(OutboxEventStatus.CLAIMED);
        crashPoisonPill.setClaimToken("crashed-worker-token");
        crashPoisonPill.setClaimedAt(LocalDateTime.now().minusMinutes(5));
        crashPoisonPill.setRetryCount(2); // Already failed/crashed twice
        crashPoisonPill.setMaxRetries(3);
        outboxEventRepository.saveAndFlush(crashPoisonPill);

        outboxEventRelay.setClaimTimeoutMs(60000);
        int published = outboxEventRelay.relayPendingEvents(10);
        assertEquals(0, published, "Crash-exhausted event must not be dispatched to broker");

        OutboxEvent terminal = outboxEventRepository.findById("crash-pill-001").orElseThrow();
        assertEquals(OutboxEventStatus.DEAD, terminal.getStatus());
        assertEquals(3, terminal.getRetryCount());
        assertTrue(terminal.getLastError().contains("Exceeded max retries"));
        assertNull(terminal.getClaimToken());
        verify(outboxMessagePublisher, never()).publish(any(OutboxEvent.class));
    }

    @Test
    void outboxSummary_reportsAccurateStatusCountsAndRecentFailures() {
        OutboxEvent e1 = new OutboxEvent("ev-1", "BOOKING", "RB-1", "BOOKING_CONFIRMED", "{}", LocalDateTime.now());
        e1.setStatus(OutboxEventStatus.PENDING);

        OutboxEvent e2 = new OutboxEvent("ev-2", "BOOKING", "RB-2", "BOOKING_CONFIRMED", "{}", LocalDateTime.now());
        e2.setStatus(OutboxEventStatus.PUBLISHED);
        e2.setPublishedAt(LocalDateTime.now());

        OutboxEvent e3 = new OutboxEvent("ev-3", "PAYMENT", "TX-3", "PAYMENT_FAILED", "{}", LocalDateTime.now());
        e3.setStatus(OutboxEventStatus.FAILED);
        e3.setRetryCount(1);
        e3.setLastError("Connection timed out");

        OutboxEvent e4 = new OutboxEvent("ev-4", "BOOKING", "RB-4", "BOOKING_CANCELLED", "{}", LocalDateTime.now());
        e4.setStatus(OutboxEventStatus.DEAD);
        e4.setRetryCount(3);
        e4.setLastError("Terminal payload failure");

        outboxEventRepository.saveAllAndFlush(List.of(e1, e2, e3, e4));

        in.yograj.learnspringboot.railway_booking.dto.OutboxSummaryDTO summary = outboxEventService.getOutboxSummary();
        assertNotNull(summary);
        assertEquals(1, summary.pendingCount());
        assertEquals(1, summary.publishedCount());
        assertEquals(1, summary.failedCount());
        assertEquals(1, summary.deadCount());
        assertEquals(0, summary.claimedCount());
        assertEquals(4, summary.totalCount());

        assertEquals(2, summary.recentFailures().size(), "Should report recent FAILED and DEAD events");
    }
}
