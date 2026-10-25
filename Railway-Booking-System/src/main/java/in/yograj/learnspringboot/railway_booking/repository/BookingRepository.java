package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    boolean existsByInventory_InventoryIdAndBookingStatus(Long inventoryId, String bookingStatus);

    boolean existsBySeatAllocation_SeatAllocationIdAndBookingStatus(Long seatAllocationId, String bookingStatus);
}
