package in.yograj.learnspringboot.railway_booking.dto;

import java.time.Instant;

public record TatkalQueueResponse(
        String queueEntryId,
        Long journeyId,
        String status,
        Instant joinedAt,
        Long position,
        Instant admittedUntil,
        String admissionPass
) {
}
