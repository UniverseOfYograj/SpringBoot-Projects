package in.yograj.learnspringboot.railway_booking.dto;

import java.util.List;

/**
 * Immutable static coach layout representation containing physical seats.
 * Dynamic inventory states are strictly excluded.
 */
public record CoachTopologyDTO(
        Long coachId,
        Long trainId,
        String coachNumber,
        String coachType,
        Integer seatCapacity,
        List<SeatTopologyDTO> seats
) {
}
