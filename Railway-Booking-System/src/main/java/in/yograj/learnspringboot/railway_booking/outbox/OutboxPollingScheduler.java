package in.yograj.learnspringboot.railway_booking.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Configurable background scheduler that periodically polls and relays staged outbox events to Kafka.
 * Activated only when Kafka publishing is explicitly enabled via railway.outbox.kafka.enabled=true.
 * Uses fixedDelay scheduling to strictly prevent overlapping cycle executions.
 */
@Component
@ConditionalOnProperty(name = "railway.outbox.kafka.enabled", havingValue = "true")
public class OutboxPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPollingScheduler.class);

    private final OutboxEventRelay outboxEventRelay;
    private final boolean schedulerEnabled;
    private final int batchSize;

    public OutboxPollingScheduler(
            OutboxEventRelay outboxEventRelay,
            @Value("${railway.outbox.scheduler.enabled:true}") boolean schedulerEnabled,
            @Value("${railway.outbox.scheduler.batch-size:50}") int batchSize) {
        this.outboxEventRelay = outboxEventRelay;
        this.schedulerEnabled = schedulerEnabled;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${railway.outbox.scheduler.fixed-delay-ms:5000}",
            initialDelayString = "${railway.outbox.scheduler.initial-delay-ms:5000}"
    )
    public void pollAndRelay() {
        if (!schedulerEnabled) {
            log.trace("Outbox polling scheduler skipped: scheduler is disabled via configuration");
            return;
        }

        try {
            log.debug("Outbox polling cycle started with batch size {}", batchSize);
            int publishedCount = outboxEventRelay.relayPendingEvents(batchSize);
            if (publishedCount > 0) {
                log.info("Outbox polling cycle completed: successfully relayed {} event(s)", publishedCount);
            } else {
                log.trace("Outbox polling cycle completed: no pending events found to relay");
            }
        } catch (Exception ex) {
            log.error("Error during outbox polling execution: {}", ex.getMessage(), ex);
        }
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public int getBatchSize() {
        return batchSize;
    }
}
