package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.SeatAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeatAllocationRepository extends JpaRepository<SeatAllocation, Long> {
    List<SeatAllocation> findByInventory_InventoryId(Long inventoryId);

    List<SeatAllocation> findByInventory_InventoryIdAndStatus(Long inventoryId, String status);
}
