package in.yograj.learnspringboot.railway_booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "razorpay_webhook_events", indexes = {
        @Index(name = "idx_razorpay_webhook_processed_at", columnList = "processed_at")
})
public class RazorpayWebhookEvent {

    @Id
    @Column(name = "event_id", length = 100)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "gateway_entity_id", length = 64)
    private String gatewayEntityId;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public RazorpayWebhookEvent() {
    }

    public RazorpayWebhookEvent(String eventId, String eventType, String gatewayEntityId,
                                LocalDateTime processedAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.gatewayEntityId = gatewayEntityId;
        this.processedAt = processedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getGatewayEntityId() {
        return gatewayEntityId;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }
}
