package in.yograj.learnspringboot.railway_booking.outbox;

import in.yograj.learnspringboot.railway_booking.entity.OutboxEvent;
import in.yograj.learnspringboot.railway_booking.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxEventService {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Staging an event inside the current active database transaction.
     * Propagation.MANDATORY ensures that this method can only be invoked within an existing transaction,
     * maintaining atomicity with the domain entity changes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent stageEvent(String aggregateType, String aggregateId, String eventType, Object eventPayload) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(eventPayload);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Failed to serialize outbox event payload to JSON", e);
        }

        String eventId = UUID.randomUUID().toString();
        OutboxEvent event = new OutboxEvent(
                eventId,
                aggregateType,
                aggregateId,
                eventType,
                payloadJson,
                LocalDateTime.now()
        );

        OutboxEvent saved = outboxEventRepository.save(event);
        log.info("Staged outbox event [eventId={}, type={}, aggregateId={}]",
                eventId, eventType, aggregateId);
        return saved;
    }
}
