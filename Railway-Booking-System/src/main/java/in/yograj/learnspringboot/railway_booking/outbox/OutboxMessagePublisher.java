package in.yograj.learnspringboot.railway_booking.outbox;

import in.yograj.learnspringboot.railway_booking.entity.OutboxEvent;

/**
 * Transport abstraction for publishing outbox events to a message broker (e.g., Apache Kafka).
 * Implementations should dispatch the event payload to the broker and throw an exception if publication fails.
 */
@FunctionalInterface
public interface OutboxMessagePublisher {
    void publish(OutboxEvent event) throws Exception;
}
