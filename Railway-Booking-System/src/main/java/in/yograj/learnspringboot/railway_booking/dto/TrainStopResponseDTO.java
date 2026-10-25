package in.yograj.learnspringboot.railway_booking.dto;

import java.time.LocalTime;

public record TrainStopResponseDTO(
        Long trainStopId,
        Long stationId,
        String stationCode,
        String stationName,
        Integer stopSequence,
        LocalTime arrivalTime,
        LocalTime departureTime,
        Integer dayOffset
) {
}
