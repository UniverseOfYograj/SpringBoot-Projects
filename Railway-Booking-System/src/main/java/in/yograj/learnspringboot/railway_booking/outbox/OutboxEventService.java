package in.yograj.learnspringboot.railway_booking.outbox;

import in.yograj.learnspringboot.railway_booking.dto.OutboxSummaryDTO;
import in.yograj.learnspringboot.railway_booking.entity.OutboxEvent;
import in.yograj.learnspringboot.railway_booking.entity.OutboxEventStatus;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Operational summary providing visibility into outbox queue health,
     * status counts, and latest failure diagnostics without external monitoring dependencies.
     */
    @Transactional(readOnly = true)
    public OutboxSummaryDTO getOutboxSummary() {
        Map<OutboxEventStatus, Long> counts = new EnumMap<>(OutboxEventStatus.class);
        for (OutboxEventStatus status : OutboxEventStatus.values()) {
            counts.put(status, 0L);
        }
        for (Object[] row : outboxEventRepository.countGroupedByStatus()) {
            OutboxEventStatus status = (OutboxEventStatus) row[0];
            Long count = (Long) row[1];
            counts.put(status, count);
        }

        List<OutboxEvent> deadAndFailed = outboxEventRepository.findTop20ByStatusInOrderByCreatedAtDesc(
                List.of(OutboxEventStatus.FAILED, OutboxEventStatus.DEAD)
        );

        List<OutboxSummaryDTO.OutboxEventDiagnosisDTO> diagnoses = deadAndFailed.stream()
                .map(e -> new OutboxSummaryDTO.OutboxEventDiagnosisDTO(
                        e.getEventId(),
                        e.getAggregateType(),
                        e.getAggregateId(),
                        e.getEventType(),
                        e.getStatus(),
                        e.getRetryCount(),
                        e.getMaxRetries(),
                        e.getLastError(),
                        e.getCreatedAt(),
                        e.getNextAttemptAt(),
                        e.getPublishedAt()
                ))
                .toList();

        long pending = counts.getOrDefault(OutboxEventStatus.PENDING, 0L);
        long claimed = counts.getOrDefault(OutboxEventStatus.CLAIMED, 0L);
        long published = counts.getOrDefault(OutboxEventStatus.PUBLISHED, 0L);
        long failed = counts.getOrDefault(OutboxEventStatus.FAILED, 0L);
        long dead = counts.getOrDefault(OutboxEventStatus.DEAD, 0L);
        long total = pending + claimed + published + failed + dead;

        return new OutboxSummaryDTO(pending, claimed, published, failed, dead, total, diagnoses);
    }
}
