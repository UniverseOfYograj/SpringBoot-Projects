package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.SeatAllocation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SeatAllocationRepository extends JpaRepository<SeatAllocation, Long> {
    List<SeatAllocation> findByInventory_InventoryId(Long inventoryId);

    List<SeatAllocation> findByInventory_InventoryIdAndStatus(Long inventoryId, String status);

    @Query("SELECT sa.seatAllocationId FROM SeatAllocation sa WHERE sa.status = 'HOLD' AND (sa.holdExpiresAt IS NULL OR sa.holdExpiresAt <= :cutoff) AND sa.seatAllocationId > :afterId ORDER BY sa.seatAllocationId ASC")
    List<Long> findExpiredHoldAllocationIdsAfter(@Param("cutoff") LocalDateTime cutoff, @Param("afterId") Long afterId, Pageable pageable);
}
