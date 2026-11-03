package in.yograj.learnspringboot.railway_booking.dto;

import java.util.List;

/**
 * Immutable static train layout representation containing coaches and seats.
 * Cacheable in Redis with 24-hour TTL to eliminate repeated PostgreSQL joins.
 */
public record TrainTopologyDTO(
        Long trainId,
        String trainNumber,
        String trainName,
        Integer totalCoaches,
        Integer totalSeats,
        List<CoachTopologyDTO> coaches
) {
}
