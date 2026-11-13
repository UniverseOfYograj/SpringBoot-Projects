package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.Train;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TrainRepository extends JpaRepository<Train, Long> {

    Optional<Train> findByTrainNumber(String trainNumber);

    @Query("""
            SELECT t FROM Train t
            WHERE t.status = 'ACTIVE'
              AND (
                  LOWER(t.trainNumber) LIKE LOWER(CONCAT(:q, '%'))
                  OR LOWER(t.trainName) LIKE LOWER(CONCAT('%', :q, '%'))
              )
            ORDER BY
              CASE
                WHEN LOWER(t.trainNumber) = LOWER(:q) THEN 1
                WHEN LOWER(t.trainNumber) LIKE LOWER(CONCAT(:q, '%')) THEN 2
                WHEN LOWER(t.trainName) LIKE LOWER(CONCAT(:q, '%')) THEN 3
                ELSE 4
              END,
              t.trainNumber ASC
            """)
    List<Train> searchByNumberOrName(@Param("q") String query, Pageable pageable);
}
