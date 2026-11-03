package in.yograj.learnspringboot.railway_booking.dto;

public record SimulatedPaymentRequestDTO(
        String idempotencyKey,
        String reason
) {
}
