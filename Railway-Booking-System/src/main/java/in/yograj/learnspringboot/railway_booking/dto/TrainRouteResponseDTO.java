package in.yograj.learnspringboot.railway_booking.dto;

import java.util.List;

public record TrainRouteResponseDTO(
        Long trainRouteId,
        Long trainId,
        String routeName,
        List<TrainStopResponseDTO> stops
) {
}
