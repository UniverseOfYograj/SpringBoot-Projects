package in.yograj.learnspringboot.railway_booking.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentResponseDTO(
        String transactionRef,
        String bookingRef,
        Long bookingId,
        BigDecimal amount,
        String paymentStatus,
        String bookingStatus,
        String paymentMethod,
        String message,
        LocalDateTime transactionTime
) {
}
