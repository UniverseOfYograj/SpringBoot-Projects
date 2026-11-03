package in.yograj.learnspringboot.railway_booking.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record SimulatedPaymentSummaryDTO(
        String bookingRef,
        Long bookingId,
        String pnr,
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
        String coachNumber,
        String coachType,
        Integer seatNumber,
        String seatType,
        BigDecimal amount,
        String bookingStatus,
        String paymentStatus,
        LocalDateTime holdExpiresAt,
        String mode
) {
}
