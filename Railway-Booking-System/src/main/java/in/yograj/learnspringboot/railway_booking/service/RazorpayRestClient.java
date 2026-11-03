package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.config.RazorpayProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

@Component
public class RazorpayRestClient implements RazorpayGatewayClient {

    private static final String API_BASE_URL = "https://api.razorpay.com/v1";

    private final RestClient restClient;
    private final RazorpayProperties properties;

    public RazorpayRestClient(RestClient.Builder builder, RazorpayProperties properties) {
        this.restClient = builder.baseUrl(API_BASE_URL).build();
        this.properties = properties;
    }

    @Override
    public GatewayOrder createOrder(long amountPaise, String receipt) {
        try {
            return restClient.post()
                    .uri("/orders")
                    .headers(this::addAuthorization)
                    .body(new CreateOrderRequest(amountPaise, "INR", receipt))
                    .retrieve()
                    .body(GatewayOrder.class);
        } catch (RestClientException exception) {
            throw new IllegalStateException("Razorpay order creation failed", exception);
        }
    }

    @Override
    public GatewayPayment fetchPayment(String paymentId) {
        try {
            JsonNode payment = restClient.get()
                    .uri("/payments/{paymentId}", paymentId)
                    .headers(this::addAuthorization)
                    .retrieve()
                    .body(JsonNode.class);
            if (payment == null) {
                return null;
            }
            return new GatewayPayment(
                    text(payment, "id"),
                    text(payment, "order_id"),
                    number(payment, "amount"),
                    text(payment, "currency"),
                    text(payment, "status"));
        } catch (RestClientException exception) {
            throw new IllegalStateException("Razorpay payment verification is temporarily unavailable", exception);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalStateException("Razorpay returned an invalid payment response");
        }
        return value.asLong();
    }

    private void addAuthorization(HttpHeaders headers) {
        headers.setBasicAuth(properties.getKeyId(), properties.getKeySecret());
    }

    private record CreateOrderRequest(long amount, String currency, String receipt) {
    }
}
