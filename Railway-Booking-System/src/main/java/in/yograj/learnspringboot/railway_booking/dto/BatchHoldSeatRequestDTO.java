package in.yograj.learnspringboot.railway_booking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BatchHoldSeatRequestDTO(
        @NotEmpty(message = "At least one seat ID must be provided") List<Long> seatIds,
        @Min(value = 1, message = "Hold duration in minutes must be at least 1") Integer holdMinutes,
        Long boardingJourneyStopId,
        Long alightingJourneyStopId,
        String travelClass,
        String coachNumber,
        BookingMode bookingMode
) {
    public BatchHoldSeatRequestDTO {
        bookingMode = bookingMode == null ? BookingMode.GENERAL : bookingMode;
    }

    public BatchHoldSeatRequestDTO(
            List<Long> seatIds,
            Integer holdMinutes,
            Long boardingJourneyStopId,
            Long alightingJourneyStopId,
            String travelClass,
            String coachNumber
    ) {
        this(seatIds, holdMinutes, boardingJourneyStopId, alightingJourneyStopId, travelClass, coachNumber, BookingMode.GENERAL);
    }
}
