package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.JourneyStop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JourneyStopRepository extends JpaRepository<JourneyStop, Long> {
    List<JourneyStop> findByJourney_JourneyIdOrderByStopSequence(Long journeyId);

    boolean existsByJourney_JourneyId(Long journeyId);
}
