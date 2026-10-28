package in.yograj.learnspringboot.railway_booking.outbox;

import in.yograj.learnspringboot.railway_booking.entity.OutboxEvent;
import in.yograj.learnspringboot.railway_booking.entity.OutboxEventStatus;
import in.yograj.learnspringboot.railway_booking.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxEventRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventRelay.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxMessagePublisher outboxMessagePublisher;
    private final TransactionTemplate transactionTemplate;

    /**
     * Discovers pending or retryable failed outbox events and publishes them via the message publisher.
     * Returns the count of successfully published events in this batch.
     */
    public int relayPendingEvents(int batchSize) {
        List<OutboxEvent> pending = outboxEventRepository.findPendingEventsForRelay(
                OutboxEventStatus.PENDING,
                OutboxEventStatus.FAILED,
                PageRequest.of(0, batchSize)
        );

        int publishedCount = 0;
        for (OutboxEvent event : pending) {
            if (processSingleEvent(event.getEventId())) {
                publishedCount++;
            }
        }
        return publishedCount;
    }

    /**
     * Processes a single outbox event with non-blocking transaction boundaries:
     * 1. The broker network publish call executes outside of any database transaction,
     *    preventing database connection pool starvation while waiting for Kafka ACKs.
     * 2. Short, isolated database transactions are opened only to record the PUBLISHED
     *    or FAILED result.
     */
    public boolean processSingleEvent(String eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElse(null);
        if (event == null || event.getStatus() == OutboxEventStatus.PUBLISHED) {
            return false;
        }

        try {
            // Network I/O outside DB transaction: Kafka broker wait does NOT hold DB connection
            outboxMessagePublisher.publish(event);

            // Short, isolated DB transaction to record successful publication
            transactionTemplate.executeWithoutResult(status -> {
                outboxEventRepository.findById(eventId).ifPresent(ev -> {
                    ev.setStatus(OutboxEventStatus.PUBLISHED);
                    ev.setPublishedAt(LocalDateTime.now());
                    ev.setLastError(null);
                    outboxEventRepository.save(ev);
                });
            });

            log.info("Outbox event published [eventId={}, type={}]", event.getEventId(), event.getEventType());
            return true;
        } catch (Exception ex) {
            String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();

            // Short, isolated DB transaction to record failure and increment retry count
            transactionTemplate.executeWithoutResult(status -> {
                outboxEventRepository.findById(eventId).ifPresent(ev -> {
                    int retries = ev.getRetryCount() + 1;
                    ev.setRetryCount(retries);
                    ev.setStatus(OutboxEventStatus.FAILED);
                    ev.setLastError(errorMsg);
                    outboxEventRepository.save(ev);
                });
            });

            log.warn("Failed to publish outbox event [eventId={}]: {}", event.getEventId(), errorMsg);
            return false;
        }
    }
}
