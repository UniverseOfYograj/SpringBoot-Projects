package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.Passenger;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PassengerRepository extends JpaRepository<Passenger, Long> {

    @EntityGraph(attributePaths = {"seatAllocation", "seat", "coach", "booking"})
    List<Passenger> findByBooking_BookingId(Long bookingId);

    @EntityGraph(attributePaths = {"seatAllocation", "seat", "coach", "booking"})
    Optional<Passenger> findBySeatAllocation_SeatAllocationId(Long seatAllocationId);

    boolean existsBySeatAllocation_SeatAllocationId(Long seatAllocationId);
}
