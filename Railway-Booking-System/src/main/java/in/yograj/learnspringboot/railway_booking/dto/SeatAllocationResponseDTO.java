package in.yograj.learnspringboot.railway_booking.dto;

import java.time.LocalDateTime;

public record SeatAllocationResponseDTO(
        Long seatAllocationId,
        Long inventoryId,
        Long journeyId,
        Long seatId,
        Long boardingJourneyStopId,
        Long alightingJourneyStopId,
        String status,
        LocalDateTime holdExpiresAt
) {
}
