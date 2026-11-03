package in.yograj.learnspringboot.railway_booking.dto;

/**
 * Immutable static seat layout representation.
 * Dynamic availability statuses, locks, allocations, and hold expirations
 * are strictly excluded to ensure authoritative state remains in PostgreSQL.
 */
public record SeatTopologyDTO(
        Long seatId,
        Long coachId,
        Integer seatNumber,
        String seatType,
        String berthType
) {
    public SeatTopologyDTO(Long seatId, Long coachId, Integer seatNumber, String seatType) {
        this(seatId, coachId, seatNumber, seatType, seatType);
    }
}
