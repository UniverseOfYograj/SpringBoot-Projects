package in.yograj.learnspringboot.railway_booking.dto;

public record RazorpayOrderResponseDTO(
        String orderId,
        String keyId,
        String bookingRef,
        long amount,
        String currency,
        String transactionRef
) {
}
