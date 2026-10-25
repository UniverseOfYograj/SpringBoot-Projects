package in.yograj.learnspringboot.railway_booking.dto;

import java.time.LocalTime;

public record TrainSearchResponseDTO(
        Long trainId,
        String trainNumber,
        String trainName,

        String fromStationCode,
        String fromStationName,
        LocalTime fromArrivalTime,
        LocalTime fromDepartureTime,
        int fromDayOffset,

        String toStationCode,
        String toStationName,
        LocalTime toArrivalTime,
        LocalTime toDepartureTime,
        int toDayOffset
) {
}