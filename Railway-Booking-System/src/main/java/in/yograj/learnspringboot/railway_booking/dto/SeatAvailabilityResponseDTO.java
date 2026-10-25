package in.yograj.learnspringboot.railway_booking.dto;

import java.time.LocalDateTime;

public record SeatAvailabilityResponseDTO(
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
}
