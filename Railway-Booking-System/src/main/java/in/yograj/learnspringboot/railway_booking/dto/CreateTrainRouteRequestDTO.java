package in.yograj.learnspringboot.railway_booking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CreateTrainRouteRequestDTO(
        @NotBlank String routeName,
        @NotEmpty List<@Valid TrainStopRequestDTO> stops
) {
}
