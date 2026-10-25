package in.yograj.learnspringboot.railway_booking.integration.railradar.mapper;

import java.time.LocalTime;
import java.util.List;

public record RailRadarTrainImport(
        String trainNumber,
        String trainName,
        List<Stop> stops
) {
    public record Stop(
            int sequence,
            String stationCode,
            LocalTime arrivalTime,
            LocalTime departureTime,
            int dayOffset
    ) {
    }
}
