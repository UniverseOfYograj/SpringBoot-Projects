package in.yograj.learnspringboot.railway_booking.service;

import java.time.LocalDateTime;

public record ReaperRunSummary(
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        long durationMs,
        int candidatesExamined,
        int allocationsExpired,
        int bookingsExpired,
        int legacyHoldsNormalized,
        int skippedCount,
        int anomalyCount,
        int failureCount
) {
}
