package in.yograj.learnspringboot.railway_booking.dto;

import in.yograj.learnspringboot.railway_booking.entity.OutboxEventStatus;

import java.time.LocalDateTime;
import java.util.List;

public record OutboxSummaryDTO(
        long pendingCount,
        long claimedCount,
        long publishedCount,
        long failedCount,
        long deadCount,
        long totalCount,
        List<OutboxEventDiagnosisDTO> recentFailures
) {
    public record OutboxEventDiagnosisDTO(
            String eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            OutboxEventStatus status,
            int retryCount,
            int maxRetries,
            String lastError,
            LocalDateTime createdAt,
            LocalDateTime nextAttemptAt,
            LocalDateTime publishedAt
    ) {}
}
