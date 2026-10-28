package in.yograj.learnspringboot.railway_booking.outbox;

import in.yograj.learnspringboot.railway_booking.entity.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OutboxConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OutboxConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(OutboxMessagePublisher.class)
    public OutboxMessagePublisher defaultOutboxMessagePublisher() {
        return event -> log.info("Default outbox message publisher dispatching [eventId={}, type={}, aggregateId={}]",
                event.getEventId(), event.getEventType(), event.getAggregateId());
    }
}
