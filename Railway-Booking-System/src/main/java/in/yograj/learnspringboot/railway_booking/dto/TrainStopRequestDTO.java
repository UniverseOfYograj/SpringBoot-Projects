package in.yograj.learnspringboot.railway_booking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public record TrainStopRequestDTO(
        @NotNull Long stationId,
        @NotNull @Min(1) Integer stopSequence,
        LocalTime arrivalTime,
        LocalTime departureTime,
        @NotNull @Min(0) Integer dayOffset
) {
}
