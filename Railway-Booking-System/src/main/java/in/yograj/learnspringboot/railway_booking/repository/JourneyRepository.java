package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.Journey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface JourneyRepository extends JpaRepository<Journey, Long> {
    List<Journey> findByFromStation_StationIdAndToStation_StationIdAndJourneyDate(Long fromStationId, Long toStationId, LocalDate journeyDate);

    List<Journey> findByJourneyDate(LocalDate journeyDate);

    boolean existsByTrain_TrainIdAndFromStation_StationIdAndToStation_StationIdAndJourneyDate(
            Long trainId,
            Long fromStationId,
            Long toStationId,
            LocalDate journeyDate
    );
}
