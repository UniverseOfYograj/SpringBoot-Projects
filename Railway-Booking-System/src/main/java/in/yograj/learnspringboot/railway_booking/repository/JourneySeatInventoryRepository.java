package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.JourneySeatInventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface JourneySeatInventoryRepository extends JpaRepository<JourneySeatInventory, Long> {
    @EntityGraph(attributePaths = {"seat", "coach", "journey"})
    List<JourneySeatInventory> findByJourney_JourneyId(Long journeyId);

    List<JourneySeatInventory> findByJourney_JourneyIdAndStatus(Long journeyId, String status);

    Optional<JourneySeatInventory> findByJourney_JourneyIdAndSeat_SeatId(Long journeyId, Long seatId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select inventory from JourneySeatInventory inventory " +
            "where inventory.journey.journeyId = :journeyId and inventory.seat.seatId = :seatId")
    Optional<JourneySeatInventory> findByJourneyAndSeatForUpdate(
            @Param("journeyId") Long journeyId,
            @Param("seatId") Long seatId
    );

    boolean existsByJourney_JourneyIdAndSeat_SeatId(Long journeyId, Long seatId);

    @Query("SELECT jsi.inventoryId FROM JourneySeatInventory jsi WHERE jsi.status = 'HOLD' AND (jsi.holdExpiresAt IS NULL OR jsi.holdExpiresAt <= :cutoff) AND jsi.inventoryId > :afterId ORDER BY jsi.inventoryId ASC")
    List<Long> findExpiredLegacyHoldInventoryIdsAfter(@Param("cutoff") LocalDateTime cutoff, @Param("afterId") Long afterId, Pageable pageable);
}
