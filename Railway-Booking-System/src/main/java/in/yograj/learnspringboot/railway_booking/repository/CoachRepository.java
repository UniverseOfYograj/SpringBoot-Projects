package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.Coach;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CoachRepository extends JpaRepository<Coach, Long> {
    List<Coach> findByTrain_TrainId(Long trainId);

    Optional<Coach> findByTrain_TrainIdAndCoachNumber(Long trainId, String coachNumber);
}
