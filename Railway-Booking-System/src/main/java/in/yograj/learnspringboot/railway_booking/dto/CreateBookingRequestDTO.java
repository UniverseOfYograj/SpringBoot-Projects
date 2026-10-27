package in.yograj.learnspringboot.railway_booking.dto;

import jakarta.validation.constraints.NotNull;

public record CreateBookingRequestDTO(
        @NotNull(message = "Journey ID is required") Long journeyId,
        @NotNull(message = "Seat ID is required") Long seatId,
        Long boardingJourneyStopId,
        Long alightingJourneyStopId,
        BookingMode bookingMode
) {
    public CreateBookingRequestDTO {
        bookingMode = bookingMode == null ? BookingMode.GENERAL : bookingMode;
    }

    public CreateBookingRequestDTO(Long journeyId, Long seatId, Long boardingJourneyStopId,
                                   Long alightingJourneyStopId) {
        this(journeyId, seatId, boardingJourneyStopId, alightingJourneyStopId, BookingMode.GENERAL);
    }
}
