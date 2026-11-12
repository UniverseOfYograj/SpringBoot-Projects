package in.yograj.learnspringboot.railway_booking.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateBookingRequestDTO(
        @NotNull(message = "Journey ID is required") Long journeyId,
        Long seatId,
        Long boardingJourneyStopId,
        Long alightingJourneyStopId,
        BookingMode bookingMode,
        String travelClass,
        String coachNumber,
        List<PassengerRequestDTO> passengers
) {
    public CreateBookingRequestDTO {
        bookingMode = bookingMode == null ? BookingMode.GENERAL : bookingMode;
        if (seatId == null && (passengers == null || passengers.isEmpty())) {
            throw new IllegalArgumentException("Either seatId or a non-empty passengers list must be provided");
        }
    }

    /**
     * Backward-compatible 4-parameter constructor (Phase 17.16).
     */
    public CreateBookingRequestDTO(
            Long journeyId,
            Long seatId,
            Long boardingJourneyStopId,
            Long alightingJourneyStopId
    ) {
        this(journeyId, seatId, boardingJourneyStopId, alightingJourneyStopId, BookingMode.GENERAL, null, null, null);
    }

    /**
     * Backward-compatible 5-parameter constructor with BookingMode (Tatkal integration).
     */
    public CreateBookingRequestDTO(
            Long journeyId,
            Long seatId,
            Long boardingJourneyStopId,
            Long alightingJourneyStopId,
            BookingMode bookingMode
    ) {
        this(journeyId, seatId, boardingJourneyStopId, alightingJourneyStopId, bookingMode, null, null, null);
    }

    /**
     * Backward-compatible 6-parameter constructor (Phase 17.17 class-aware).
     */
    public CreateBookingRequestDTO(
            Long journeyId,
            Long seatId,
            Long boardingJourneyStopId,
            Long alightingJourneyStopId,
            String travelClass,
            String coachNumber
    ) {
        this(journeyId, seatId, boardingJourneyStopId, alightingJourneyStopId, BookingMode.GENERAL, travelClass, coachNumber, null);
    }

    /**
     * Backward-compatible 7-parameter constructor (Phase 17.18 multi-passenger).
     */
    public CreateBookingRequestDTO(
            Long journeyId,
            Long seatId,
            Long boardingJourneyStopId,
            Long alightingJourneyStopId,
            String travelClass,
            String coachNumber,
            List<PassengerRequestDTO> passengers
    ) {
        this(journeyId, seatId, boardingJourneyStopId, alightingJourneyStopId, BookingMode.GENERAL, travelClass, coachNumber, passengers);
    }
}
