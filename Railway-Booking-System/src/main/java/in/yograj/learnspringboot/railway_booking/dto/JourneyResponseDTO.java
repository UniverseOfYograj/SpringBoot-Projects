package in.yograj.learnspringboot.railway_booking.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record JourneyResponseDTO(
        Long journeyId,
        Long trainId,
        String trainNumber,
        String trainName,
        Long fromStationId,
        String fromStationCode,
        String fromStationName,
        Long toStationId,
        String toStationCode,
        String toStationName,
        LocalDate journeyDate,
        LocalTime departureTime,
        LocalTime arrivalTime,
        Long trainRouteId,
        LocalDateTime departureAt,
        LocalDateTime arrivalAt
) {
}
