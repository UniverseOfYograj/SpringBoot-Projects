package in.yograj.learnspringboot.railway_booking.outbox;

import in.yograj.learnspringboot.railway_booking.entity.ProcessedConsumerEvent;
import in.yograj.learnspringboot.railway_booking.repository.ProcessedConsumerEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class IdempotentEventConsumerService {

    private static final Logger log = LoggerFactory.getLogger(IdempotentEventConsumerService.class);

    private final ProcessedConsumerEventRepository processedEventRepository;

    /**
     * Executes the given handler idempotently for the specified consumer.
     * If the event has already been processed by this consumer, execution is skipped.
     * Concurrent duplicates are suppressed via the unique constraint on (event_id, consumer_name).
     *
     * @return true if the event was processed for the first time, false if duplicate skipped
     */
    @Transactional
    public boolean processIdempotently(String eventId, String consumerName, String eventType, Consumer<String> eventHandler) {
        if (processedEventRepository.existsByEventIdAndConsumerName(eventId, consumerName)) {
            log.info("Duplicate event ignored [eventId={}, consumer={}]", eventId, consumerName);
            return false;
        }

        try {
            eventHandler.accept(eventId);

            ProcessedConsumerEvent record = new ProcessedConsumerEvent(
                    eventId,
                    consumerName,
                    eventType,
                    LocalDateTime.now()
            );
            processedEventRepository.saveAndFlush(record);
            return true;
        } catch (DataIntegrityViolationException dive) {
            log.warn("Concurrent duplicate event suppressed [eventId={}, consumer={}]", eventId, consumerName);
            return false;
        }
    }
}
