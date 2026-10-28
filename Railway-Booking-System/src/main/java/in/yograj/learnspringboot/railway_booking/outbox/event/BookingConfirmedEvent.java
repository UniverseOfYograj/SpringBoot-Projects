package in.yograj.learnspringboot.railway_booking.outbox.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BookingConfirmedEvent(
        String bookingRef,
        String pnr,
        Long bookingId,
        Long userId,
        String username,
        Long journeyId,
        BigDecimal amount,
        String paymentMethod,
        String transactionRef,
        String status,
        LocalDateTime occurredAt
) {}
