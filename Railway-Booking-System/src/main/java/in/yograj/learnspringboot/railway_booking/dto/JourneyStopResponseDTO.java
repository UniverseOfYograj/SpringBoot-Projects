package in.yograj.learnspringboot.railway_booking.dto;

import java.time.LocalDateTime;

public record JourneyStopResponseDTO(
        Long journeyStopId,
        Long stationId,
        String stationCode,
        String stationName,
        Integer stopSequence,
        LocalDateTime arrivalAt,
        LocalDateTime departureAt
) {
}
