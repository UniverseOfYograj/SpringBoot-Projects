package in.yograj.learnspringboot.railway_booking.outbox;

import in.yograj.learnspringboot.railway_booking.entity.OutboxEvent;
import in.yograj.learnspringboot.railway_booking.entity.OutboxEventStatus;
import in.yograj.learnspringboot.railway_booking.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxEventRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventRelay.class);
    private static final long BASE_RETRY_DELAY_SECONDS = 1;
    private static final long MAX_RETRY_DELAY_SECONDS = 300;

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxMessagePublisher outboxMessagePublisher;
    private final TransactionTemplate transactionTemplate;

    @Autowired(required = false)
    @Setter
    private in.yograj.learnspringboot.railway_booking.observability.RailoraMetrics railoraMetrics;

    @Setter
    @Value("${railway.outbox.scheduler.claim-timeout-ms:60000}")
    private long claimTimeoutMs;

    /**
     * Claims eligible rows in a short transaction, then publishes outside the transaction
     * so waiting for broker acknowledgements does not hold database locks or connections.
     * Kafka acknowledgement followed by a failed database update can still cause redelivery;
     * this remains at-least-once delivery and consumers should deduplicate by event ID.
     */
    public int relayPendingEvents(int batchSize) {
        if (batchSize <= 0) {
            return 0;
        }
        if (railoraMetrics != null) {
            railoraMetrics.refreshOutboxGaugesIfExpired();
        }
        int publishedCount = 0;
        for (int i = 0; i < batchSize; i++) {
            OutboxEvent event = claimNextPendingEvent();
            if (event == null) {
                break;
            }
            if (publishClaimedEvent(event)) {
                publishedCount++;
            }
        }
        return publishedCount;
    }

    public boolean processSingleEvent(String eventId) {
        OutboxEvent claimed = claimSingleEvent(eventId);
        return claimed != null && publishClaimedEvent(claimed);
    }

    private OutboxEvent claimNextPendingEvent() {
        List<OutboxEvent> claimed = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            List<OutboxEvent> events = outboxEventRepository.findClaimableEvents(
                    now,
                    now.minusNanos(claimTimeoutMs * 1_000_000L),
                    1
            );
            claim(events, now);
            return events;
        });
        if (claimed == null) {
            throw new IllegalStateException("Outbox claim transaction completed without a result");
        }
        return claimed.stream().findFirst().orElse(null);
    }

    private OutboxEvent claimSingleEvent(String eventId) {
        OutboxEvent claimed = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            List<OutboxEvent> events = outboxEventRepository.findClaimableEventById(
                    eventId,
                    now,
                    now.minusNanos(claimTimeoutMs * 1_000_000L)
            );
            claim(events, now);
            return events.stream().findFirst().orElse(null);
        });
        return claimed;
    }

    private void claim(List<OutboxEvent> events, LocalDateTime now) {
        Iterator<OutboxEvent> iterator = events.iterator();
        while (iterator.hasNext()) {
            OutboxEvent event = iterator.next();
            if (event.getStatus() == OutboxEventStatus.CLAIMED) {
                // Reclaiming a stale claim after worker crash or lease expiry
                int updatedRetries = event.getRetryCount() + 1;
                event.setRetryCount(updatedRetries);
                log.warn("Reclaimed stale outbox claim [eventId={}, previousClaimToken={}, previousClaimedAt={}, newRetryCount={}]",
                        event.getEventId(), event.getClaimToken(), event.getClaimedAt(), updatedRetries);

                if (updatedRetries >= event.getMaxRetries()) {
                    event.setStatus(OutboxEventStatus.DEAD);
                    event.setLastError("Exceeded max retries due to worker crash or claim timeout");
                    event.setClaimToken(null);
                    event.setClaimedAt(null);
                    event.setNextAttemptAt(null);
                    iterator.remove(); // do not return for publishing
                    continue;
                }
            }
            event.setStatus(OutboxEventStatus.CLAIMED);
            event.setClaimToken(UUID.randomUUID().toString());
            event.setClaimedAt(now);
        }
        outboxEventRepository.saveAll(events);
        outboxEventRepository.flush();
    }

    private boolean publishClaimedEvent(OutboxEvent event) {
        String eventId = event.getEventId();
        String claimToken = event.getClaimToken();
        long startNanos = System.nanoTime();
        try {
            outboxMessagePublisher.publish(event);
            if (railoraMetrics != null) {
                railoraMetrics.recordOutboxPublishDuration(event.getEventType(), System.nanoTime() - startNanos);
            }
        } catch (Exception ex) {
            String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            int retryCount = event.getRetryCount() + 1;
            boolean terminal = retryCount >= event.getMaxRetries();
            if (railoraMetrics != null) {
                railoraMetrics.recordOutboxPublishAttempt(event.getEventType(), terminal ? "terminal_dead" : "retryable_failure");
            }
            LocalDateTime nextAttemptAt = LocalDateTime.now().plusSeconds(retryDelaySeconds(retryCount));
            int updated = outboxEventRepository.markFailed(
                    eventId,
                    claimToken,
                    OutboxEventStatus.CLAIMED,
                    OutboxEventStatus.FAILED,
                    OutboxEventStatus.DEAD,
                    errorMsg,
                    nextAttemptAt
            );
            if (updated == 0) {
                log.warn("Outbox failure ignored because claim is no longer owned [eventId={}, error={}]",
                        eventId, errorMsg);
                return false;
            }

            log.warn("Failed to publish outbox event [eventId={}, retryCount={}, terminal={}]: {}",
                    eventId, retryCount, terminal, errorMsg);
            return false;
        }

        int updated = outboxEventRepository.markPublished(
                eventId,
                claimToken,
                OutboxEventStatus.CLAIMED,
                OutboxEventStatus.PUBLISHED,
                LocalDateTime.now()
        );
        if (updated == 0) {
            log.warn("Outbox publication acknowledgement ignored because claim is no longer owned [eventId={}]",
                    eventId);
            return false;
        }

        if (railoraMetrics != null) {
            railoraMetrics.recordOutboxPublishAttempt(event.getEventType(), "success");
            railoraMetrics.refreshOutboxGaugesIfExpired();
        }

        log.info("Outbox event published [eventId={}, type={}]", eventId, event.getEventType());
        return true;
    }

    private long retryDelaySeconds(int retryCount) {
        int exponent = Math.min(Math.max(retryCount - 1, 0), 30);
        return Math.min(BASE_RETRY_DELAY_SECONDS << exponent, MAX_RETRY_DELAY_SECONDS);
    }
}
