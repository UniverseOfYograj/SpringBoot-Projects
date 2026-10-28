package in.yograj.learnspringboot.railway_booking.outbox.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentFailedEvent(
        String bookingRef,
        String transactionRef,
        BigDecimal amount,
        String paymentMethod,
        String failureReason,
        LocalDateTime occurredAt
) {}
