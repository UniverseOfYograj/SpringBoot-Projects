package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findByCoach_CoachId(Long coachId);

    Optional<Seat> findByCoach_CoachIdAndSeatNumber(Long coachId, Integer seatNumber);
}
