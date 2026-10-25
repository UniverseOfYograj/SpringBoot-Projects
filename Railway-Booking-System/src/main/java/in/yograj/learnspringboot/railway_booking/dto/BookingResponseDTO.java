package in.yograj.learnspringboot.railway_booking.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BookingResponseDTO(
        Long bookingId,
        String bookingRef,
        Long journeyId,
        Long seatId,
        String coachNumber,
        Integer seatNumber,
        String bookingStatus,
        String paymentStatus,
        BigDecimal amount,
        LocalDateTime createdAt
) {
}
