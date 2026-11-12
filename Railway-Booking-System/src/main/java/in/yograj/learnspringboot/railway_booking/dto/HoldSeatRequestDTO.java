package in.yograj.learnspringboot.railway_booking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record HoldSeatRequestDTO(
        @NotNull(message = "Seat ID is required") Long seatId,
        @Min(value = 1, message = "Hold duration in minutes must be at least 1") Integer holdMinutes,
        Long boardingJourneyStopId,
        Long alightingJourneyStopId,
        BookingMode bookingMode,
        String travelClass,
        String coachNumber
) {
    public HoldSeatRequestDTO {
        bookingMode = bookingMode == null ? BookingMode.GENERAL : bookingMode;
    }

    public HoldSeatRequestDTO(Long seatId, Integer holdMinutes, Long boardingJourneyStopId,
                              Long alightingJourneyStopId) {
        this(seatId, holdMinutes, boardingJourneyStopId, alightingJourneyStopId, BookingMode.GENERAL, null, null);
    }

    public HoldSeatRequestDTO(Long seatId, Integer holdMinutes, Long boardingJourneyStopId,
                              Long alightingJourneyStopId, BookingMode bookingMode) {
        this(seatId, holdMinutes, boardingJourneyStopId, alightingJourneyStopId, bookingMode, null, null);
    }

    public HoldSeatRequestDTO(Long seatId, Integer holdMinutes, Long boardingJourneyStopId,
                              Long alightingJourneyStopId, String travelClass, String coachNumber) {
        this(seatId, holdMinutes, boardingJourneyStopId, alightingJourneyStopId, BookingMode.GENERAL, travelClass, coachNumber);
    }
}
