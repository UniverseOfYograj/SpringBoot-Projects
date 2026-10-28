package in.yograj.learnspringboot.railway_booking.outbox;

import in.yograj.learnspringboot.railway_booking.entity.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Apache Kafka implementation of OutboxMessagePublisher.
 * Dispatches outbox events to a configured Kafka topic using KafkaTemplate.
 * Waits synchronously for broker acknowledgment to guarantee that network or broker failures
 * bubble up to OutboxEventRelay, leaving events retryable in the outbox table.
 */
@Component
@ConditionalOnProperty(name = "railway.outbox.kafka.enabled", havingValue = "true")
public class KafkaOutboxMessagePublisher implements OutboxMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxMessagePublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final long timeoutMs;

    public KafkaOutboxMessagePublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${railway.outbox.kafka.topic:railora-booking-events}") String topic,
            @Value("${railway.outbox.kafka.timeout-ms:5000}") long timeoutMs) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void publish(OutboxEvent event) throws Exception {
        String key = event.getAggregateId();
        String payload = event.getPayload();

        log.debug("Publishing outbox event to Kafka [topic={}, eventId={}, key={}]", topic, event.getEventId(), key);

        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, key, payload);
        SendResult<String, String> sendResult = future.get(timeoutMs, TimeUnit.MILLISECONDS);

        log.info("Published outbox event to Kafka [topic={}, partition={}, offset={}, eventId={}]",
                topic,
                sendResult != null && sendResult.getRecordMetadata() != null ? sendResult.getRecordMetadata().partition() : -1,
                sendResult != null && sendResult.getRecordMetadata() != null ? sendResult.getRecordMetadata().offset() : -1,
                event.getEventId());
    }

    public String getTopic() {
        return topic;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }
}
