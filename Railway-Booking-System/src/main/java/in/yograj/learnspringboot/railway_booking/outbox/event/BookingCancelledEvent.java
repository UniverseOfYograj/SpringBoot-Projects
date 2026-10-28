package in.yograj.learnspringboot.railway_booking.outbox.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BookingCancelledEvent(
        String bookingRef,
        String pnr,
        Long bookingId,
        Long userId,
        String username,
        String bookingStatus,
        String paymentStatus,
        BigDecimal refundAmount,
        LocalDateTime occurredAt
) {}
