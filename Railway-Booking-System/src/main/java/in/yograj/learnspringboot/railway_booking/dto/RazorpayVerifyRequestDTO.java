package in.yograj.learnspringboot.railway_booking.dto;

public record RazorpayVerifyRequestDTO(
        String orderId,
        String paymentId,
        String signature
) {
}
