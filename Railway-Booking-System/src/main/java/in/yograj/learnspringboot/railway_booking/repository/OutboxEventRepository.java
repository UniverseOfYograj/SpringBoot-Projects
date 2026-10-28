package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.OutboxEvent;
import in.yograj.learnspringboot.railway_booking.entity.OutboxEventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    @Query("SELECT e FROM OutboxEvent e WHERE e.status = :pendingStatus OR (e.status = :failedStatus AND e.retryCount < e.maxRetries) ORDER BY e.createdAt ASC")
    List<OutboxEvent> findPendingEventsForRelay(
            @Param("pendingStatus") OutboxEventStatus pendingStatus,
            @Param("failedStatus") OutboxEventStatus failedStatus,
            Pageable pageable);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(String aggregateId);

    List<OutboxEvent> findByStatus(OutboxEventStatus status);
}
