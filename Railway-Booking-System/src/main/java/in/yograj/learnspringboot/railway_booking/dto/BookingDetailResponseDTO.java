package in.yograj.learnspringboot.railway_booking.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record BookingDetailResponseDTO(
        Long bookingId,
        String bookingRef,
        Long userId,
        String username,
        Long journeyId,
        String trainNumber,
        String trainName,
        String fromStationCode,
        String fromStationName,
        String toStationCode,
        String toStationName,
        LocalDate journeyDate,
        LocalTime departureTime,
        LocalTime arrivalTime,
        Long coachId,
        String coachNumber,
        String coachType,
        Long seatId,
        Integer seatNumber,
        String seatType,
        Long seatAllocationId,
        String bookingStatus,
        String paymentStatus,
        BigDecimal amount,
        LocalDateTime createdAt
) {
}
