package in.yograj.learnspringboot.railway_booking.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "processed_consumer_events", uniqueConstraints = {
        @UniqueConstraint(name = "uk_processed_consumer_events", columnNames = {"event_id", "consumer_name"})
})
@Getter
@Setter
@NoArgsConstructor
public class ProcessedConsumerEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "consumer_name", nullable = false, length = 100)
    private String consumerName;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public ProcessedConsumerEvent(String eventId, String consumerName, String eventType, LocalDateTime processedAt) {
        this.eventId = eventId;
        this.consumerName = consumerName;
        this.eventType = eventType;
        this.processedAt = processedAt;
    }
}
