package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.OutboxEvent;
import in.yograj.learnspringboot.railway_booking.entity.OutboxEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    @Query(value = """
            SELECT *
            FROM outbox_events
            WHERE status = 'PENDING'
               OR (status = 'FAILED' AND retry_count < max_retries
                   AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
               OR (status = 'CLAIMED' AND claimed_at <= :staleClaimBefore
                   AND retry_count < max_retries)
            ORDER BY created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findClaimableEvents(
            @Param("now") LocalDateTime now,
            @Param("staleClaimBefore") LocalDateTime staleClaimBefore,
            @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE OutboxEvent event
            SET event.status = :status,
                event.publishedAt = :publishedAt,
                event.lastError = null,
                event.claimToken = null,
                event.claimedAt = null,
                event.nextAttemptAt = null
            WHERE event.eventId = :eventId
              AND event.status = :claimedStatus
              AND event.claimToken = :claimToken
            """)
    int markPublished(
            @Param("eventId") String eventId,
            @Param("claimToken") String claimToken,
            @Param("claimedStatus") OutboxEventStatus claimedStatus,
            @Param("status") OutboxEventStatus status,
            @Param("publishedAt") LocalDateTime publishedAt);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE OutboxEvent event
            SET event.retryCount = event.retryCount + 1,
                event.status = CASE WHEN event.retryCount + 1 >= event.maxRetries
                                    THEN :terminalStatus ELSE :failedStatus END,
                event.lastError = :lastError,
                event.claimToken = null,
                event.claimedAt = null,
                event.nextAttemptAt = :nextAttemptAt
            WHERE event.eventId = :eventId
              AND event.status = :claimedStatus
              AND event.claimToken = :claimToken
            """)
    int markFailed(
            @Param("eventId") String eventId,
            @Param("claimToken") String claimToken,
            @Param("claimedStatus") OutboxEventStatus claimedStatus,
            @Param("failedStatus") OutboxEventStatus failedStatus,
            @Param("terminalStatus") OutboxEventStatus terminalStatus,
            @Param("lastError") String lastError,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    @Query(value = """
            SELECT *
            FROM outbox_events
            WHERE event_id = :eventId
              AND (status = 'PENDING'
                   OR (status = 'FAILED' AND retry_count < max_retries
                       AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
                   OR (status = 'CLAIMED' AND claimed_at <= :staleClaimBefore
                       AND retry_count < max_retries))
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findClaimableEventById(
            @Param("eventId") String eventId,
            @Param("now") LocalDateTime now,
            @Param("staleClaimBefore") LocalDateTime staleClaimBefore);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(String aggregateId);

    List<OutboxEvent> findByStatus(OutboxEventStatus status);

    long countByStatus(OutboxEventStatus status);

    List<OutboxEvent> findTop20ByStatusInOrderByCreatedAtDesc(List<OutboxEventStatus> statuses);

    @Query("""
            SELECT event.status, COUNT(event)
            FROM OutboxEvent event
            GROUP BY event.status
            """)
    List<Object[]> countGroupedByStatus();
}
