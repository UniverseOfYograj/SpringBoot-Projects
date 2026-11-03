package in.yograj.learnspringboot.railway_booking.outbox;

import in.yograj.learnspringboot.railway_booking.entity.ProcessedConsumerEvent;
import in.yograj.learnspringboot.railway_booking.repository.ProcessedConsumerEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class IdempotentEventConsumerService {

    private static final Logger log = LoggerFactory.getLogger(IdempotentEventConsumerService.class);

    private final ProcessedConsumerEventRepository processedEventRepository;
    private final TransactionTemplate transactionTemplate;

    /**
     * Writes and flushes the deduplication marker before invoking the handler in the same
     * transaction. Database-local handler effects therefore commit or roll back with the marker.
     * The unique constraint serializes concurrent duplicate deliveries before either handler runs.
     * External effects performed by the handler cannot be made atomic with this database transaction;
     * those integrations must use eventId as an idempotency key or their own transactional outbox.
     *
     * @return true if the event was processed for the first time, false if duplicate skipped
     */
    public boolean processIdempotently(String eventId, String consumerName, String eventType, Consumer<String> eventHandler) {
        try {
            Boolean processed = transactionTemplate.execute(status -> {
                if (processedEventRepository.existsByEventIdAndConsumerName(eventId, consumerName)) {
                    return false;
                }

                ProcessedConsumerEvent record = new ProcessedConsumerEvent(
                        eventId,
                        consumerName,
                        eventType,
                        LocalDateTime.now()
                );
                processedEventRepository.saveAndFlush(record);
                eventHandler.accept(eventId);
                return true;
            });
            if (processed == null) {
                throw new IllegalStateException("Consumer transaction completed without a processing result");
            }
            return processed;
        } catch (DataIntegrityViolationException dive) {
            if (processedEventRepository.existsByEventIdAndConsumerName(eventId, consumerName)) {
                log.info("Concurrent duplicate event ignored [eventId={}, consumer={}]", eventId, consumerName);
                return false;
            }
            throw dive;
        }
    }
}
