package in.yograj.learnspringboot.railway_booking.dto;

import jakarta.validation.constraints.NotNull;

public record CreateBookingRequestDTO(
        @NotNull(message = "Journey ID is required") Long journeyId,
        @NotNull(message = "Seat ID is required") Long seatId,
        Long boardingJourneyStopId,
        Long alightingJourneyStopId
) {
}
