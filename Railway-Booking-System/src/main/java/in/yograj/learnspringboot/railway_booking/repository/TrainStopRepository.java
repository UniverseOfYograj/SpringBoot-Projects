package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.TrainStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TrainStopRepository extends JpaRepository<TrainStop, Long> {

    List<TrainStop> findByTrainRoute_TrainRouteIdOrderByStopSequence(Long trainRouteId);

    @Query("""
            SELECT fromStop, toStop
            FROM TrainStop fromStop
            JOIN fromStop.trainRoute route
            JOIN route.train train
            JOIN fromStop.station fromStation
            JOIN TrainStop toStop
                ON toStop.trainRoute = route
            JOIN toStop.station toStation
            WHERE fromStation.stationCode = :fromCode
              AND toStation.stationCode = :toCode
              AND fromStop.stopSequence < toStop.stopSequence
              AND train.status = 'ACTIVE'
            ORDER BY train.trainNumber
            """)
    List<Object[]> findTrainStopsBetweenStations(
            @Param("fromCode") String fromCode,
            @Param("toCode") String toCode);
}