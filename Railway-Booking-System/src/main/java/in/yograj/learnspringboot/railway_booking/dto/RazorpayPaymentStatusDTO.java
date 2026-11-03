package in.yograj.learnspringboot.railway_booking.dto;

public record RazorpayPaymentStatusDTO(
        String transactionRef,
        String bookingRef,
        String paymentStatus,
        String bookingStatus,
        String message
) {
}
