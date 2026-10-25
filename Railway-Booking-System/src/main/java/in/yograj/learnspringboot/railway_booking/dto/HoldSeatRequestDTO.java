package in.yograj.learnspringboot.railway_booking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record HoldSeatRequestDTO(
        @NotNull(message = "Seat ID is required") Long seatId,
        @Min(value = 1, message = "Hold duration in minutes must be at least 1") Integer holdMinutes,
        Long boardingJourneyStopId,
        Long alightingJourneyStopId
) {
}
