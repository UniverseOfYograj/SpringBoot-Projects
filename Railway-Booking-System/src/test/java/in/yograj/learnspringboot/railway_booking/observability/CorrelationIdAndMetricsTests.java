package in.yograj.learnspringboot.railway_booking.observability;

import in.yograj.learnspringboot.railway_booking.dto.ApiErrorResponse;
import in.yograj.learnspringboot.railway_booking.dto.PaymentRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentResponseDTO;
import in.yograj.learnspringboot.railway_booking.entity.OutboxEventStatus;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.BookingRepository;
import in.yograj.learnspringboot.railway_booking.repository.OutboxEventRepository;
import in.yograj.learnspringboot.railway_booking.security.CorrelationIdFilter;
import in.yograj.learnspringboot.railway_booking.service.BookingService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:correlation-metrics-tests;DB_CLOSE_DELAY=-1",
        "DB_USERNAME=sa",
        "DB_PASSWORD=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:correlation-metrics-tests;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length"
})
class CorrelationIdAndMetricsTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private RailoraMetrics railoraMetrics;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // -----------------------------------------------------------------------------------
    // Part A: Correlation ID Tests
    // -----------------------------------------------------------------------------------

    @Test
    void correlationId_whenMissing_generatesUuidAndEchoesInResponse() throws Exception {
        MvcResult result = mockMvc.perform(get("/livez"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andReturn();

        String generatedId = result.getResponse().getHeader("X-Request-Id");
        assertNotNull(generatedId);
        // Verify it is a valid UUID
        assertDoesNotThrow(() -> UUID.fromString(generatedId));
    }

    @Test
    void correlationId_whenValidHeaderProvided_retainsIncomingId() throws Exception {
        String customId = "my-custom-request-id-12345";
        mockMvc.perform(get("/livez").header("X-Request-Id", customId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", customId));
    }

    @Test
    void correlationId_whenUnsafeOrMalformed_replacesWithSecureUuid() throws Exception {
        String dangerousInput = "invalid_id<script>alert(1)</script>%n%s!@#;";
        MvcResult result = mockMvc.perform(get("/livez").header("X-Request-Id", dangerousInput))
                .andExpect(status().isOk())
                .andReturn();

        String responseId = result.getResponse().getHeader("X-Request-Id");
        assertNotNull(responseId);
        assertNotEquals(dangerousInput, responseId, "Unsafe header must be replaced");
        assertDoesNotThrow(() -> UUID.fromString(responseId), "Replaced value must be a safe UUID");
    }

    @Test
    void correlationId_whenOversized_replacesWithSecureUuid() throws Exception {
        String oversized = "a".repeat(100); // Exceeds 64 chars limit
        MvcResult result = mockMvc.perform(get("/livez").header("X-Request-Id", oversized))
                .andExpect(status().isOk())
                .andReturn();

        String responseId = result.getResponse().getHeader("X-Request-Id");
        assertNotNull(responseId);
        assertNotEquals(oversized, responseId, "Oversized header must be replaced");
        assertDoesNotThrow(() -> UUID.fromString(responseId));
    }

    @Test
    void correlationId_filterCleansUpMdcEvenOnException() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/test/exception");
        MockHttpServletResponse res = new MockHttpServletResponse();

        MockFilterChain failingChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                // Verify MDC is populated during request execution
                assertNotNull(MDC.get(CorrelationIdFilter.MDC_KEY), "MDC must contain correlation ID during request");
                throw new RuntimeException("Simulated controller failure");
            }
        };

        assertThrows(RuntimeException.class, () -> correlationIdFilter.doFilter(req, res, failingChain));

        // After filter completes (even exceptionally), MDC must be clean
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY), "MDC must be cleared in finally block");
    }

    @Test
    void correlationId_concurrentRequests_maintainIsolatedMdc() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String assignedId = "req-thread-" + i + "-" + UUID.randomUUID().toString().substring(0, 8);
            futures.add(executor.submit(() -> {
                startLatch.await();
                MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
                request.addHeader("X-Request-Id", assignedId);
                MockHttpServletResponse response = new MockHttpServletResponse();

                final boolean[] matched = {false};
                correlationIdFilter.doFilter(request, response, (req, res) -> {
                    String mdcValue = MDC.get(CorrelationIdFilter.MDC_KEY);
                    matched[0] = assignedId.equals(mdcValue);
                });

                boolean mdcCleaned = (MDC.get(CorrelationIdFilter.MDC_KEY) == null);
                boolean headerEchoed = assignedId.equals(response.getHeader("X-Request-Id"));
                return matched[0] && mdcCleaned && headerEchoed;
            }));
        }

        startLatch.countDown();
        for (Future<Boolean> f : futures) {
            assertTrue(f.get(10, TimeUnit.SECONDS), "Concurrent request must isolate MDC without cross-talk");
        }
        executor.shutdown();
    }

    @Test
    void apiErrorResponse_includesCorrelationIdFromMdc() {
        String testCorrelationId = "test-corr-id-999";
        MDC.put(CorrelationIdFilter.MDC_KEY, testCorrelationId);
        try {
            ApiErrorResponse error = ApiErrorResponse.of(400, "Bad Request", "Invalid input", "/api/test");
            assertEquals(testCorrelationId, error.correlationId());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    // -----------------------------------------------------------------------------------
    // Part C: Business Metrics Tests
    // -----------------------------------------------------------------------------------

    @Test
    void businessMetrics_bookingCreationOutcome_incrementsCounterWithBoundedTags() {
        double beforeSuccess = getCounterCount("railora.booking.created", "type", "standard", "outcome", "success", "failure_reason", "none");
        double beforeFail = getCounterCount("railora.booking.created", "type", "standard", "outcome", "failure", "failure_reason", "seat_conflict");

        railoraMetrics.recordBookingCreated("standard", "success", "none");
        railoraMetrics.recordBookingCreated("standard", "failure", "seat_conflict");

        assertEquals(beforeSuccess + 1.0, getCounterCount("railora.booking.created", "type", "standard", "outcome", "success", "failure_reason", "none"));
        assertEquals(beforeFail + 1.0, getCounterCount("railora.booking.created", "type", "standard", "outcome", "failure", "failure_reason", "seat_conflict"));
    }

    @Test
    void businessMetrics_paymentOutcomes_tracksSuccessFailureAndIdempotentReplaySeparately() {
        double beforeSuccess = getCounterCount("railora.payment.attempts", "method", "simulated", "outcome", "success", "failure_reason", "none");
        double beforeReplay = getCounterCount("railora.payment.attempts", "method", "simulated", "outcome", "idempotent_replay", "none");
        double beforeFailure = getCounterCount("railora.payment.attempts", "method", "simulated", "outcome", "failure", "failure_reason", "simulation");

        // First attempt succeeds
        railoraMetrics.recordPaymentAttempt("SIMULATED", "success", "none");
        // Idempotent retry of same request
        railoraMetrics.recordPaymentAttempt("SIMULATED", "idempotent_replay", "none");
        // Simulated failure attempt
        railoraMetrics.recordPaymentAttempt("SIMULATED", "failure", "simulation");

        // Verify counts
        assertEquals(beforeSuccess + 1.0, getCounterCount("railora.payment.attempts", "method", "simulated", "outcome", "success", "failure_reason", "none"));
        assertEquals(beforeReplay + 1.0, getCounterCount("railora.payment.attempts", "method", "simulated", "outcome", "idempotent_replay", "failure_reason", "none"),
                "Idempotent replay must NOT double-count as a new payment success");
        assertEquals(beforeFailure + 1.0, getCounterCount("railora.payment.attempts", "method", "simulated", "outcome", "failure", "failure_reason", "simulation"));
    }

    @Test
    void businessMetrics_seatConflict_recordsOverlapAndGeneralConflicts() {
        double beforeOverlap = getCounterCount("railora.seat.allocation.conflicts", "conflict_type", "overlap_exclusion");

        railoraMetrics.recordSeatConflict("overlap_exclusion");

        assertEquals(beforeOverlap + 1.0, getCounterCount("railora.seat.allocation.conflicts", "conflict_type", "overlap_exclusion"));
    }

    @Test
    void businessMetrics_tatkalQueue_recordsBoundedOutcomes() {
        double beforeAdmitted = getCounterCount("railora.tatkal.queue.requests", "action", "join", "outcome", "admitted");
        double beforeRateLimited = getCounterCount("railora.tatkal.queue.requests", "action", "any", "outcome", "rate_limited");

        railoraMetrics.recordTatkalQueue("join", "admitted");
        railoraMetrics.recordTatkalQueue("any", "rate_limited");

        assertEquals(beforeAdmitted + 1.0, getCounterCount("railora.tatkal.queue.requests", "action", "join", "outcome", "admitted"));
        assertEquals(beforeRateLimited + 1.0, getCounterCount("railora.tatkal.queue.requests", "action", "any", "outcome", "rate_limited"));
    }

    @Test
    void businessMetrics_outboxPublishDurationAndAttempts() {
        double beforeAttempts = getCounterCount("railora.outbox.publish.attempts", "event_type", "booking_confirmed", "outcome", "success");

        railoraMetrics.recordOutboxPublishDuration("BOOKING_CONFIRMED", 15_000_000L); // 15ms
        railoraMetrics.recordOutboxPublishAttempt("BOOKING_CONFIRMED", "success");

        assertEquals(beforeAttempts + 1.0, getCounterCount("railora.outbox.publish.attempts", "event_type", "booking_confirmed", "outcome", "success"));
    }

    @Test
    void businessMetrics_outboxGauges_maintainAccurateCountsWithoutDatabaseSpam() {
        railoraMetrics.setOutboxGaugeValue(OutboxEventStatus.PENDING, 5);
        railoraMetrics.setOutboxGaugeValue(OutboxEventStatus.FAILED, 3);
        railoraMetrics.setOutboxGaugeValue(OutboxEventStatus.DEAD, 2);

        assertEquals(5, railoraMetrics.getOutboxGaugeValue(OutboxEventStatus.PENDING));
        assertEquals(3, railoraMetrics.getOutboxGaugeValue(OutboxEventStatus.FAILED));
        assertEquals(2, railoraMetrics.getOutboxGaugeValue(OutboxEventStatus.DEAD));

        // Read through MeterRegistry gauge
        assertEquals(5.0, meterRegistry.get("railora.outbox.events").tag("status", "PENDING").gauge().value());
        assertEquals(3.0, meterRegistry.get("railora.outbox.events").tag("status", "FAILED").gauge().value());
        assertEquals(2.0, meterRegistry.get("railora.outbox.events").tag("status", "DEAD").gauge().value());
    }

    @Test
    void outboxGauges_coversAllFiveStates_includingFailed() {
        for (OutboxEventStatus status : OutboxEventStatus.values()) {
            railoraMetrics.setOutboxGaugeValue(status, 10L);
            assertNotNull(meterRegistry.find("railora.outbox.events").tag("status", status.name()).gauge(),
                    "Gauge must exist for status " + status.name());
            assertEquals(10.0, meterRegistry.get("railora.outbox.events").tag("status", status.name()).gauge().value());
        }
    }

    @Test
    void asyncMdcPropagation_propagatesCallerContextToWorkerThread() throws Exception {
        MdcTaskDecorator decorator = new MdcTaskDecorator();
        MDC.put("correlationId", "caller-trace-456");
        try {
            CompletableFuture<String> workerMdc = new CompletableFuture<>();
            Runnable decorated = decorator.decorate(() -> workerMdc.complete(MDC.get("correlationId")));

            // Run on a separate executor thread
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(decorated).get(5, TimeUnit.SECONDS);
                assertEquals("caller-trace-456", workerMdc.get(5, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }
        } finally {
            MDC.remove("correlationId");
        }
    }

    @Test
    void asyncMdcCleanup_clearsWorkerThreadMdcAfterExecution() throws Exception {
        MdcTaskDecorator decorator = new MdcTaskDecorator();
        MDC.put("correlationId", "temp-caller-id");
        try {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Runnable decorated = decorator.decorate(() -> {
                    assertEquals("temp-caller-id", MDC.get("correlationId"));
                });
                executor.submit(decorated).get(5, TimeUnit.SECONDS);

                // Verify the worker thread's MDC is completely clean after task execution
                Future<String> workerMdcAfter = executor.submit(() -> MDC.get("correlationId"));
                assertNull(workerMdcAfter.get(5, TimeUnit.SECONDS),
                        "Worker thread MDC must be empty after task completion (no ThreadLocal leakage)");
            } finally {
                executor.shutdownNow();
            }
        } finally {
            MDC.remove("correlationId");
        }
    }

    @Test
    void asyncMdcRestoration_restoresPreExistingWorkerContext() throws Exception {
        MdcTaskDecorator decorator = new MdcTaskDecorator();
        MDC.put("correlationId", "child-caller-id");
        try {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                // Pre-populate worker thread's MDC
                executor.submit(() -> MDC.put("correlationId", "worker-original-id")).get(5, TimeUnit.SECONDS);

                Runnable decorated = decorator.decorate(() -> {
                    assertEquals("child-caller-id", MDC.get("correlationId"));
                });
                executor.submit(decorated).get(5, TimeUnit.SECONDS);

                // Verify the worker thread's original MDC was restored
                Future<String> workerMdcAfter = executor.submit(() -> MDC.get("correlationId"));
                assertEquals("worker-original-id", workerMdcAfter.get(5, TimeUnit.SECONDS),
                        "Worker thread's pre-existing MDC must be restored after task completion");
            } finally {
                executor.shutdownNow();
            }
        } finally {
            MDC.remove("correlationId");
        }
    }

    @Test
    void asyncMdcScheduledJob_withoutCallerContext_clearsWorkerMdc() throws Exception {
        MdcTaskDecorator decorator = new MdcTaskDecorator();
        // Caller has NO MDC context (simulating a scheduled task or background job)
        MDC.clear();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // Suppose worker thread has stale leftover MDC
            executor.submit(() -> MDC.put("correlationId", "stale-thread-leak")).get(5, TimeUnit.SECONDS);

            CompletableFuture<String> insideTaskMdc = new CompletableFuture<>();
            Runnable decorated = decorator.decorate(() -> insideTaskMdc.complete(MDC.get("correlationId")));

            executor.submit(decorated).get(5, TimeUnit.SECONDS);
            assertNull(insideTaskMdc.get(5, TimeUnit.SECONDS),
                    "When caller has no MDC context, worker thread MDC must be cleared during execution");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void correlationId_asyncServletDispatch_retainsSameCorrelationId() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // 1. Initial request dispatch without header generates an ID
        filter.doFilter(request, response, chain);
        String initialId = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertNotNull(initialId);
        assertEquals(initialId, request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE));

        // 2. Simulated ASYNC dispatch on the same request object
        MockHttpServletResponse asyncResponse = new MockHttpServletResponse();
        MockFilterChain asyncChain = new MockFilterChain();
        filter.doFilter(request, asyncResponse, asyncChain);

        assertEquals(initialId, asyncResponse.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER),
                "Async servlet dispatch must retain the exact same correlation ID");
    }

    @Test
    void businessMetrics_bookingCancellation_recordsOutcomes() {
        double beforeSuccess = getCounterCount("railora.booking.cancellations", "outcome", "success", "failure_reason", "none");
        double beforeAlreadyCancelled = getCounterCount("railora.booking.cancellations", "outcome", "failure", "failure_reason", "already_cancelled");

        railoraMetrics.recordBookingCancellation("success", "none");
        railoraMetrics.recordBookingCancellation("failure", "already_cancelled");

        assertEquals(beforeSuccess + 1.0, getCounterCount("railora.booking.cancellations", "outcome", "success", "failure_reason", "none"));
        assertEquals(beforeAlreadyCancelled + 1.0, getCounterCount("railora.booking.cancellations", "outcome", "failure", "failure_reason", "already_cancelled"));
    }

    @Test
    void businessMetrics_razorpay_recordsPaymentOutcomes() {
        double beforeSuccess = getCounterCount("railora.payment.attempts", "method", "razorpay", "outcome", "success", "failure_reason", "none");
        double beforeReplay = getCounterCount("railora.payment.attempts", "method", "razorpay", "outcome", "idempotent_replay", "failure_reason", "none");
        double beforeFailed = getCounterCount("railora.payment.attempts", "method", "razorpay", "outcome", "failed", "failure_reason", "declined");

        railoraMetrics.recordPaymentAttempt("RAZORPAY", "success", "none");
        railoraMetrics.recordPaymentAttempt("RAZORPAY", "idempotent_replay", "none");
        railoraMetrics.recordPaymentAttempt("RAZORPAY", "failed", "declined");

        assertEquals(beforeSuccess + 1.0, getCounterCount("railora.payment.attempts", "method", "razorpay", "outcome", "success", "failure_reason", "none"));
        assertEquals(beforeReplay + 1.0, getCounterCount("railora.payment.attempts", "method", "razorpay", "outcome", "idempotent_replay", "failure_reason", "none"));
        assertEquals(beforeFailed + 1.0, getCounterCount("railora.payment.attempts", "method", "razorpay", "outcome", "failed", "failure_reason", "declined"));
    }

    private double getCounterCount(String name, String... tags) {
        try {
            Counter counter = meterRegistry.find(name).tags(tags).counter();
            return counter != null ? counter.count() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }
}
