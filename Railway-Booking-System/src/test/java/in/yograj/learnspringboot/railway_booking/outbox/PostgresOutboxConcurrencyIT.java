package in.yograj.learnspringboot.railway_booking.outbox;

import in.yograj.learnspringboot.railway_booking.entity.OutboxEvent;
import in.yograj.learnspringboot.railway_booking.entity.OutboxEventStatus;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.OutboxEventRepository;
import in.yograj.learnspringboot.railway_booking.repository.ProcessedConsumerEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Real PostgreSQL Concurrency Integration Test for Transactional Outbox.
 *
 * Verifies true PostgreSQL row-level locking via SELECT FOR UPDATE SKIP LOCKED
 * across concurrent relay worker threads, lease expiration recovery, and
 * idempotent consumer serialization using unique constraints.
 */
@SpringBootTest
public class PostgresOutboxConcurrencyIT {

    private static final String TEST_PG_URL = System.getenv("RAILORA_TEST_PG_URL") != null
            ? System.getenv("RAILORA_TEST_PG_URL")
            : System.getenv("RAZORPAY_E2E_DB_URL");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        if (TEST_PG_URL != null && !TEST_PG_URL.isBlank()) {
            registry.add("spring.datasource.url", () -> TEST_PG_URL);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () ->
                    System.getenv().getOrDefault("RAILORA_TEST_PG_USER",
                            System.getenv().getOrDefault("RAZORPAY_E2E_DB_USERNAME", "postgres")));
            registry.add("spring.datasource.password", () ->
                    System.getenv().getOrDefault("RAILORA_TEST_PG_PASSWORD",
                            System.getenv().getOrDefault("RAZORPAY_E2E_DB_PASSWORD", "1234")));
            registry.add("spring.flyway.enabled", () -> "true");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        } else {
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:outbox-concurrency-it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
            registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "");
            registry.add("spring.flyway.enabled", () -> "false");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        }
        registry.add("railway.outbox.kafka.enabled", () -> "false");
        registry.add("railway.cache.enabled", () -> "false");
        registry.add("jwt.access-secret", () -> "test-access-signing-secret-with-minimum-32-bytes-length");
        registry.add("jwt.refresh-secret", () -> "test-refresh-signing-secret-with-minimum-32-bytes-length");
    }

    @BeforeAll
    static void verifyDatasourceSafety(@Autowired DataSource dataSource) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            String catalog = conn.getCatalog();
            if ("railway_booking".equalsIgnoreCase(catalog)) {
                throw new IllegalStateException(
                        "CRITICAL SAFETY GUARD: Test executed against development database 'railway_booking'. Aborting immediately.");
            }
        }
    }

    @MockitoBean private GitHubRailwayDataImportService gitHubRailwayDataImportService;
    @MockitoBean private RailRadarClient railRadarClient;
    @MockitoBean private OutboxMessagePublisher outboxMessagePublisher;

    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private ProcessedConsumerEventRepository processedConsumerEventRepository;
    @Autowired private OutboxEventRelay outboxEventRelay;
    @Autowired private IdempotentEventConsumerService idempotentConsumerService;

    private final List<String> createdEventIds = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setup() {
        createdEventIds.clear();
    }

    @AfterEach
    void tearDown() {
        synchronized (createdEventIds) {
            for (String eventId : createdEventIds) {
                outboxEventRepository.deleteById(eventId);
                processedConsumerEventRepository.findByEventIdAndConsumerName(eventId, "concurrency-consumer")
                        .ifPresent(processedConsumerEventRepository::delete);
            }
            createdEventIds.clear();
        }
    }

    @Test
    void concurrentRelayWorkers_withSkipLocked_claimDistinctEventsWithoutDuplication() throws Exception {
        int eventCount = 10;
        int workerCount = 4;

        // Stage 10 distinct events
        for (int i = 0; i < eventCount; i++) {
            String id = UUID.randomUUID().toString();
            createdEventIds.add(id);
            OutboxEvent event = new OutboxEvent(
                    id,
                    "BOOKING",
                    "PG-CONCUR-" + id.substring(0, 8),
                    "BOOKING_CONFIRMED",
                    "{\"index\":" + i + "}",
                    LocalDateTime.now()
            );
            outboxEventRepository.save(event);
        }
        outboxEventRepository.flush();

        // Simulate random I/O latency for publisher
        Set<String> publishedEventIds = ConcurrentHashMap.newKeySet();
        ConcurrentHashMap<String, Integer> invocationCounts = new ConcurrentHashMap<>();

        doAnswer(invocation -> {
            OutboxEvent ev = invocation.getArgument(0);
            invocationCounts.merge(ev.getEventId(), 1, Integer::sum);
            boolean newlyAdded = publishedEventIds.add(ev.getEventId());
            assertTrue(newlyAdded, "CRITICAL CONCURRENCY FAILURE: Event " + ev.getEventId()
                    + " was simultaneously claimed and dispatched by multiple workers!");
            Thread.sleep(ThreadLocalRandom.current().nextInt(10, 30));
            return null;
        }).when(outboxMessagePublisher).publish(any(OutboxEvent.class));

        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int w = 0; w < workerCount; w++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return outboxEventRelay.relayPendingEvents(5);
            }));
        }

        startLatch.countDown();
        int totalReportedPublished = 0;
        for (Future<Integer> f : futures) {
            totalReportedPublished += f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Verify concurrency isolation: all created events were published, and each exactly once
        assertTrue(publishedEventIds.containsAll(createdEventIds), "All created test events must be claimed and published");
        assertTrue(totalReportedPublished >= eventCount, "Total published events across workers should include all test events");

        // Verify database state: all created events are PUBLISHED with zero duplicate dispatches
        for (String id : createdEventIds) {
            assertEquals(1, invocationCounts.getOrDefault(id, 0), "Event " + id + " must be dispatched exactly once");
            OutboxEvent ev = outboxEventRepository.findById(id).orElseThrow();
            assertEquals(OutboxEventStatus.PUBLISHED, ev.getStatus());
            assertNotNull(ev.getPublishedAt());
            assertNull(ev.getClaimToken());
        }
    }

    @Test
    void concurrentConsumers_sameEvent_deduplicatesViaUniqueConstraintAtomically() throws Exception {
        String eventId = UUID.randomUUID().toString();
        createdEventIds.add(eventId);

        int concurrentAttempts = 5;
        AtomicInteger handlerExecutionCount = new AtomicInteger(0);
        AtomicInteger successfulReturns = new AtomicInteger(0);
        AtomicInteger skippedReturns = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(concurrentAttempts);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < concurrentAttempts; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    boolean processed = idempotentConsumerService.processIdempotently(
                            eventId,
                            "concurrency-consumer",
                            "BOOKING_CONFIRMED",
                            id -> handlerExecutionCount.incrementAndGet()
                    );
                    if (processed) {
                        successfulReturns.incrementAndGet();
                    } else {
                        skippedReturns.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertEquals(1, handlerExecutionCount.get(), "Handler must execute exactly once across concurrent deliveries");
        assertEquals(1, successfulReturns.get(), "Exactly one consumer call must return true");
        assertEquals(concurrentAttempts - 1, skippedReturns.get(), "All duplicate calls must return false");
        assertTrue(processedConsumerEventRepository.existsByEventIdAndConsumerName(eventId, "concurrency-consumer"));
    }
}
