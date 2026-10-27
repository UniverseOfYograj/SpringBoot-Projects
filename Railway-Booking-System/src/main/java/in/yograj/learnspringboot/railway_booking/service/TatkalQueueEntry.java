package in.yograj.learnspringboot.railway_booking.service;

import java.time.Instant;

public record TatkalQueueEntry(
        String entryId,
        Long journeyId,
        String username,
        String status,
        Instant joinedAt,
        long sequence,
        String admissionId,
        Instant admittedAt,
        Instant admittedUntil,
        Long heldSeatId
) {
}
