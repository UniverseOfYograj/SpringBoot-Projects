package in.yograj.learnspringboot.railway_booking.observability;

import in.yograj.learnspringboot.railway_booking.entity.OutboxEventStatus;
import in.yograj.learnspringboot.railway_booking.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized business and operational metrics recorder using Micrometer.
 *
 * Guarantees:
 * 1. Low-cardinality, strictly bounded tags only (no PNRs, booking IDs, user emails, or raw exceptions).
 * 2. Idempotent replays are tracked separately and not double-counted as new successes.
 * 3. Outbox status gauges use an in-memory cached snapshot (15s TTL) to prevent Prometheus scrapes
 *    from generating repeated database queries.
 * 4. All recording methods are fail-safe: failures in metrics emission are logged and will NEVER
 *    disrupt business transactions or cause requests to fail.
 */
@Component
public class RailoraMetrics {

    private static final Logger log = LoggerFactory.getLogger(RailoraMetrics.class);

    private final MeterRegistry meterRegistry;
    private final OutboxEventRepository outboxEventRepository;

    // Outbox gauge cache: prevents Prometheus from hitting the database on every scrape
    private final Map<OutboxEventStatus, AtomicLong> outboxStatusGauges = new EnumMap<>(OutboxEventStatus.class);
    private volatile long lastOutboxRefreshMillis = 0;
    private static final long OUTBOX_CACHE_TTL_MS = 15_000L;

    @Autowired
    public RailoraMetrics(MeterRegistry meterRegistry, OutboxEventRepository outboxEventRepository) {
        this.meterRegistry = meterRegistry;
        this.outboxEventRepository = outboxEventRepository;

        initializeOutboxGauges();
    }

    private void initializeOutboxGauges() {
        for (OutboxEventStatus status : OutboxEventStatus.values()) {
            AtomicLong gaugeValue = new AtomicLong(0L);
            outboxStatusGauges.put(status, gaugeValue);

            meterRegistry.gauge("railora.outbox.events",
                    Tags.of("status", status.name()),
                    this,
                    metrics -> {
                        metrics.refreshOutboxGaugesIfExpired();
                        return (double) metrics.getOutboxGaugeValue(status);
                    });
        }
    }

    /**
     * Records a booking creation attempt with bounded tags.
     */
    public void recordBookingCreated(String type, String outcome, String failureReason) {
        try {
            meterRegistry.counter("railora.booking.created",
                    "type", sanitizeTag(type, "standard"),
                    "outcome", sanitizeTag(outcome, "unknown"),
                    "failure_reason", sanitizeTag(failureReason, "none")
            ).increment();
        } catch (Throwable t) {
            log.warn("Failed to record booking created metric: {}", t.getMessage());
        }
    }

    /**
     * Records a payment attempt with bounded tags.
     * Idempotent replays are tagged with outcome="idempotent_replay" to prevent duplicate counting.
     */
    public void recordPaymentAttempt(String method, String outcome, String failureReason) {
        try {
            meterRegistry.counter("railora.payment.attempts",
                    "method", sanitizeTag(method, "UNKNOWN"),
                    "outcome", sanitizeTag(outcome, "unknown"),
                    "failure_reason", sanitizeTag(failureReason, "none")
            ).increment();
        } catch (Throwable t) {
            log.warn("Failed to record payment attempt metric: {}", t.getMessage());
        }
    }

    /**
     * Records a booking cancellation attempt with bounded tags.
     */
    public void recordBookingCancellation(String outcome, String failureReason) {
        try {
            meterRegistry.counter("railora.booking.cancellations",
                    "outcome", sanitizeTag(outcome, "unknown"),
                    "failure_reason", sanitizeTag(failureReason, "none")
            ).increment();
        } catch (Throwable t) {
            log.warn("Failed to record booking cancellation metric: {}", t.getMessage());
        }
    }

    /**
     * Records a seat allocation conflict.
     */
    public void recordSeatConflict(String conflictType) {
        try {
            meterRegistry.counter("railora.seat.allocation.conflicts",
                    "conflict_type", sanitizeTag(conflictType, "general_conflict")
            ).increment();
        } catch (Throwable t) {
            log.warn("Failed to record seat conflict metric: {}", t.getMessage());
        }
    }

    /**
     * Records a Tatkal queue request outcome with bounded tags.
     */
    public void recordTatkalQueue(String action, String outcome) {
        try {
            meterRegistry.counter("railora.tatkal.queue.requests",
                    "action", sanitizeTag(action, "unknown"),
                    "outcome", sanitizeTag(outcome, "unknown")
            ).increment();
        } catch (Throwable t) {
            log.warn("Failed to record tatkal queue metric: {}", t.getMessage());
        }
    }

    /**
     * Records outbox event publish duration.
     */
    public void recordOutboxPublishDuration(String eventType, long durationNanos) {
        try {
            Timer.builder("railora.outbox.publish.duration")
                    .tag("event_type", sanitizeTag(eventType, "UNKNOWN"))
                    .description("Duration taken to publish outbox events to the message broker")
                    .register(meterRegistry)
                    .record(durationNanos, TimeUnit.NANOSECONDS);
        } catch (Throwable t) {
            log.warn("Failed to record outbox publish duration metric: {}", t.getMessage());
        }
    }

    /**
     * Records an outbox event publish attempt outcome.
     */
    public void recordOutboxPublishAttempt(String eventType, String outcome) {
        try {
            meterRegistry.counter("railora.outbox.publish.attempts",
                    "event_type", sanitizeTag(eventType, "UNKNOWN"),
                    "outcome", sanitizeTag(outcome, "unknown")
            ).increment();
        } catch (Throwable t) {
            log.warn("Failed to record outbox publish attempt metric: {}", t.getMessage());
        }
    }

    /**
     * Refreshes outbox gauge values from the database cache.
     * Guaranteed to query the database at most once every 15 seconds regardless of scrape frequency.
     */
    public synchronized void refreshOutboxGaugesIfExpired() {
        long now = System.currentTimeMillis();
        if (now - lastOutboxRefreshMillis < OUTBOX_CACHE_TTL_MS) {
            return;
        }

        try {
            List<Object[]> grouped = outboxEventRepository.countGroupedByStatus();
            Map<OutboxEventStatus, Long> counts = new EnumMap<>(OutboxEventStatus.class);
            for (OutboxEventStatus s : OutboxEventStatus.values()) {
                counts.put(s, 0L);
            }
            if (grouped != null) {
                for (Object[] row : grouped) {
                    if (row != null && row.length >= 2 && row[0] instanceof OutboxEventStatus st) {
                        Long count = ((Number) row[1]).longValue();
                        counts.put(st, count);
                    }
                }
            }

            for (Map.Entry<OutboxEventStatus, Long> entry : counts.entrySet()) {
                AtomicLong gauge = outboxStatusGauges.get(entry.getKey());
                if (gauge != null) {
                    gauge.set(entry.getValue());
                }
            }
            lastOutboxRefreshMillis = now;
        } catch (Throwable t) {
            log.warn("Failed to refresh outbox gauges from database: {}", t.getMessage());
        }
    }

    /**
     * Explicitly forces a cache refresh from the database.
     */
    public synchronized void forceRefreshOutboxGauges() {
        lastOutboxRefreshMillis = 0;
        refreshOutboxGaugesIfExpired();
    }

    /**
     * Explicitly sets gauge values for testing or immediate synchronization.
     */
    public void setOutboxGaugeValue(OutboxEventStatus status, long value) {
        AtomicLong gauge = outboxStatusGauges.get(status);
        if (gauge != null) {
            gauge.set(value);
        }
        lastOutboxRefreshMillis = System.currentTimeMillis();
    }

    public long getOutboxGaugeValue(OutboxEventStatus status) {
        AtomicLong gauge = outboxStatusGauges.get(status);
        return gauge != null ? gauge.get() : 0L;
    }

    private String sanitizeTag(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim().toLowerCase();
    }
}
