package in.yograj.learnspringboot.railway_booking.integration.railradar.dto;

import java.util.List;

public record RailRadarTrainDetailsDTO(
        TrainInfo train,
        List<RouteStop> route
) {
    public record TrainInfo(
            String number,
            String name
    ) {
    }

    public record StationInfo(
            String code,
            String name
    ) {
    }

    public record RouteStop(
            Integer sequence,
            StationInfo station,
            String arrival,
            String departure,
            Integer arrivalDay,
            Integer departureDay
    ) {
    }
}
