package in.yograj.learnspringboot.railway_booking.dto;

import java.math.BigDecimal;
import java.util.List;

public record TravelClassAvailabilityDTO(
        String classCode,
        String className,
        int totalSeats,
        int availableSeats,
        int heldSeats,
        int bookedSeats,
        BigDecimal baseFare,
        List<CoachAvailabilityDTO> coaches
) {
}
