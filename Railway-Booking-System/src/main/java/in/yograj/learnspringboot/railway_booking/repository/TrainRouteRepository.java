package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.TrainRoute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrainRouteRepository extends JpaRepository<TrainRoute, Long> {
    List<TrainRoute> findByTrain_TrainIdOrderByRouteName(Long trainId);

    boolean existsByTrain_TrainIdAndRouteName(Long trainId, String routeName);
}
