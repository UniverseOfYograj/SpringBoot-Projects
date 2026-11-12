package in.yograj.learnspringboot.railway_booking;

import in.yograj.learnspringboot.railway_booking.dto.TrainSearchResponseDTO;
import in.yograj.learnspringboot.railway_booking.repository.TrainStopRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates DTO projection queries against the live verified local PostgreSQL dataset:
 * - 4,513 train_routes
 * - 283,416 train_stops
 * Corridors:
 * - STA (728) -> KTE (4941): exactly 20 direct trains
 * - HWH (1844) -> NDLS (4): exactly 7 direct trains
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/railway_booking",
        "spring.datasource.username=postgres",
        "spring.datasource.password=1234",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false"
})
class LiveLocalPostgresTopologySearchIntegrationTests {

    @Autowired
    private TrainStopRepository trainStopRepository;

    @Test
    @DisplayName("Verify STA -> KTE returns exactly 20 direct trains with complete DTO contract mapping")
    void verifyStaToKteReturns20Trains() {
        long startNanos = System.nanoTime();
        List<TrainSearchResponseDTO> trains =
                trainStopRepository.findTrainStopsBetweenStations("STA", "KTE");
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertNotNull(trains);
        assertEquals(20, trains.size(), "STA -> KTE must return exactly 20 direct trains in verified topology");

        // Verify contract fields on all 20 returned DTOs
        for (TrainSearchResponseDTO t : trains) {
            assertNotNull(t.trainId(), "trainId must be present");
            assertNotNull(t.trainNumber(), "trainNumber must be present");
            assertNotNull(t.trainName(), "trainName must be present");

            assertEquals("STA", t.fromStationCode());
            assertEquals("SATNA", t.fromStationName());
            assertEquals("KTE", t.toStationCode());
            assertEquals("KATNI", t.toStationName());

            assertTrue(t.fromDayOffset() >= 0, "fromDayOffset must be >= 0");
            assertTrue(t.toDayOffset() >= t.fromDayOffset(), "toDayOffset must be >= fromDayOffset");
        }

        // Verify ordering: trains must be sorted ascending by trainNumber
        for (int i = 0; i < trains.size() - 1; i++) {
            assertTrue(trains.get(i).trainNumber().compareTo(trains.get(i + 1).trainNumber()) <= 0,
                    "Results must be ordered by trainNumber ASC");
        }

        System.out.println("STA -> KTE Query with DTO Projection Execution Time: " + durationMs + " ms");
    }

    @Test
    @DisplayName("Verify HWH -> NDLS returns exactly 7 direct trains with complete DTO contract mapping")
    void verifyHwhToNdlsReturns7Trains() {
        long startNanos = System.nanoTime();
        List<TrainSearchResponseDTO> trains =
                trainStopRepository.findTrainStopsBetweenStations("HWH", "NDLS");
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertNotNull(trains);
        assertEquals(7, trains.size(), "HWH -> NDLS must return exactly 7 direct trains in verified topology");

        for (TrainSearchResponseDTO t : trains) {
            assertNotNull(t.trainId());
            assertNotNull(t.trainNumber());
            assertNotNull(t.trainName());

            assertEquals("HWH", t.fromStationCode());
            assertEquals("HOWRAH JN", t.fromStationName());
            assertEquals("NDLS", t.toStationCode());
            assertEquals("NEW DELHI", t.toStationName());
        }

        System.out.println("HWH -> NDLS Query with DTO Projection Execution Time: " + durationMs + " ms");
    }
}
