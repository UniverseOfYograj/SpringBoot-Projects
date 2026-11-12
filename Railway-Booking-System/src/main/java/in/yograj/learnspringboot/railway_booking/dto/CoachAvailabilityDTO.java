package in.yograj.learnspringboot.railway_booking.dto;

import java.util.List;

public record CoachAvailabilityDTO(
        Long coachId,
        String coachNumber,
        String coachType,
        int totalSeats,
        int availableSeats,
        int heldSeats,
        int bookedSeats,
        List<SeatAvailabilityResponseDTO> seats
) {
}
