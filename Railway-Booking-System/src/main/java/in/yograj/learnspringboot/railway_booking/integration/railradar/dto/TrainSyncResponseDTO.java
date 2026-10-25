package in.yograj.learnspringboot.railway_booking.integration.railradar.dto;

public record TrainSyncResponseDTO(
        String trainNumber,
        Long trainId,
        String routeName,
        int trainInserted,
        int trainUpdated,
        int stopsInserted,
        boolean routeReused
) {
}
