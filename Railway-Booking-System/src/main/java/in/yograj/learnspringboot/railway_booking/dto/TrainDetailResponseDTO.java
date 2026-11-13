package in.yograj.learnspringboot.railway_booking.dto;

import java.util.List;

public record TrainDetailResponseDTO(
        Long trainId,
        String trainNumber,
        String trainName,
        String status,
        String operatingDays,
        String classProfile,
        List<TrainRouteResponseDTO> routes
) {
}
