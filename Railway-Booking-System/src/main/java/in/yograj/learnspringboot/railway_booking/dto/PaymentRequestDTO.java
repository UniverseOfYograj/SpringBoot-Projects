package in.yograj.learnspringboot.railway_booking.dto;

public record PaymentRequestDTO(
        String paymentMethod,
        String idempotencyKey,
        Boolean simulateFailure
) {
    public PaymentRequestDTO {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            paymentMethod = "MOCK_UPI";
        }
        if (simulateFailure == null) {
            simulateFailure = false;
        }
    }
}
