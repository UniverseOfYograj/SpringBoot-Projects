package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.Booking;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    boolean existsByInventory_InventoryIdAndBookingStatus(Long inventoryId, String bookingStatus);

    boolean existsBySeatAllocation_SeatAllocationIdAndBookingStatus(Long seatAllocationId, String bookingStatus);

    Optional<Booking> findBySeatAllocation_SeatAllocationId(Long seatAllocationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"seatAllocation", "user", "journey", "coach", "seat"})
    @Query("SELECT b FROM Booking b WHERE b.seatAllocation.seatAllocationId = :seatAllocationId")
    Optional<Booking> findBySeatAllocationIdForUpdate(@Param("seatAllocationId") Long seatAllocationId);

    @EntityGraph(attributePaths = {
            "seatAllocation", "user", "journey", "journey.train",
            "journey.fromStation", "journey.toStation", "coach", "seat"
    })
    Optional<Booking> findByBookingRef(String bookingRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"seatAllocation", "user", "journey", "coach", "seat"})
    @Query("SELECT b FROM Booking b WHERE b.bookingRef = :bookingRef")
    Optional<Booking> findByBookingRefForUpdate(@Param("bookingRef") String bookingRef);

    @EntityGraph(attributePaths = {
            "seatAllocation", "user", "journey", "journey.train",
            "journey.fromStation", "journey.toStation", "coach", "seat"
    })
    Page<Booking> findByUser_UserId(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = {
            "seatAllocation", "user", "journey", "journey.train",
            "journey.fromStation", "journey.toStation", "coach", "seat"
    })
    Page<Booking> findAll(Pageable pageable);
}
