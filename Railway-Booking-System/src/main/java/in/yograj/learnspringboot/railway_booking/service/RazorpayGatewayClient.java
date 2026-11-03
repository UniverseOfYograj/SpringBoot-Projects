package in.yograj.learnspringboot.railway_booking.service;

public interface RazorpayGatewayClient {

    GatewayOrder createOrder(long amountPaise, String receipt);

    GatewayPayment fetchPayment(String paymentId);

    record GatewayOrder(String id, long amount, String currency, String receipt) {
    }

    record GatewayPayment(String id, String orderId, long amount, String currency, String status) {
    }
}
