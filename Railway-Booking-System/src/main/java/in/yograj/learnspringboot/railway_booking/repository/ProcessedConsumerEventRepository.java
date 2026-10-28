package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.ProcessedConsumerEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProcessedConsumerEventRepository extends JpaRepository<ProcessedConsumerEvent, Long> {
    boolean existsByEventIdAndConsumerName(String eventId, String consumerName);
    Optional<ProcessedConsumerEvent> findByEventIdAndConsumerName(String eventId, String consumerName);
}
