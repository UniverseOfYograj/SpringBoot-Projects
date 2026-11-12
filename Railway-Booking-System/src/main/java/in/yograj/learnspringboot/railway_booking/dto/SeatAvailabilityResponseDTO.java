package in.yograj.learnspringboot.railway_booking.dto;

import java.time.LocalDateTime;

public record SeatAvailabilityResponseDTO(
        Long inventoryId,
        Long journeyId,
        Long coachId,
        String coachNumber,
        String coachType,
        Long seatId,
        Integer seatNumber,
        String seatType,
        String status,
        LocalDateTime holdExpiresAt,
        LocalDateTime updatedAt,
        Long seatAllocationId,
        Long boardingJourneyStopId,
        Long alightingJourneyStopId
) {
    /**
     * Backward-compatible 13-parameter constructor for existing callers and tests.
     */
    public SeatAvailabilityResponseDTO(
            Long inventoryId,
            Long journeyId,
            Long coachId,
            String coachNumber,
            Long seatId,
            Integer seatNumber,
            String seatType,
            String status,
            LocalDateTime holdExpiresAt,
            LocalDateTime updatedAt,
            Long seatAllocationId,
            Long boardingJourneyStopId,
            Long alightingJourneyStopId
    ) {
        this(
                inventoryId,
                journeyId,
                coachId,
                coachNumber,
                null,
                seatId,
                seatNumber,
                seatType,
                status,
                holdExpiresAt,
                updatedAt,
                seatAllocationId,
                boardingJourneyStopId,
                alightingJourneyStopId
        );
    }
}
