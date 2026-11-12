package in.yograj.learnspringboot.railway_booking.dto;

public record PassengerResponseDTO(
        Long passengerId,
        String fullName,
        Integer age,
        String gender,
        String berthPreference,
        Long seatId,
        Integer seatNumber,
        String seatType,
        Long coachId,
        String coachNumber,
        String coachType,
        Long seatAllocationId
) {
}
