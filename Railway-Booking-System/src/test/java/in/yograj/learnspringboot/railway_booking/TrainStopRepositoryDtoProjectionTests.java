package in.yograj.learnspringboot.railway_booking;

import in.yograj.learnspringboot.railway_booking.dto.TrainSearchResponseDTO;
import in.yograj.learnspringboot.railway_booking.entity.Station;
import in.yograj.learnspringboot.railway_booking.entity.Train;
import in.yograj.learnspringboot.railway_booking.entity.TrainRoute;
import in.yograj.learnspringboot.railway_booking.entity.TrainStop;
import in.yograj.learnspringboot.railway_booking.repository.StationRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainRouteRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainStopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:train-search-dto-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class TrainStopRepositoryDtoProjectionTests {

    @Autowired
    private TrainStopRepository trainStopRepository;

    @Autowired
    private TrainRepository trainRepository;

    @Autowired
    private StationRepository stationRepository;

    @Autowired
    private TrainRouteRepository trainRouteRepository;

    private Station staStation;
    private Station kteStation;
    private Station hwhStation;
    private Station ndlsStation;

    @BeforeEach
    void setUp() {
        trainStopRepository.deleteAll();
        trainRouteRepository.deleteAll();
        trainRepository.deleteAll();
        stationRepository.deleteAll();

        staStation = createStation("STA", "SATNA");
        kteStation = createStation("KTE", "KATNI");
        hwhStation = createStation("HWH", "HOWRAH JN");
        ndlsStation = createStation("NDLS", "NEW DELHI");
    }

    @Test
    @DisplayName("DTO constructor projection returns strongly-typed DTOs with all 13 fields mapped accurately")
    void verifyDtoProjectionFieldMappingAndOrder() {
        // Setup 1 active train with a 2-stop route from STA to KTE
        Train train = new Train();
        train.setTrainNumber("11072");
        train.setTrainName("Kamayani Express");
        train.setStatus("ACTIVE");
        train = trainRepository.save(train);

        TrainRoute route = new TrainRoute();
        route.setTrain(train);
        route.setRouteName("STA-KTE Route 1");
        route = trainRouteRepository.save(route);

        TrainStop stop1 = new TrainStop();
        stop1.setTrainRoute(route);
        stop1.setStation(staStation);
        stop1.setStopSequence(1);
        stop1.setArrivalTime(LocalTime.of(22, 40));
        stop1.setDepartureTime(LocalTime.of(22, 45));
        stop1.setDayOffset(0);
        trainStopRepository.save(stop1);

        TrainStop stop2 = new TrainStop();
        stop2.setTrainRoute(route);
        stop2.setStation(kteStation);
        stop2.setStopSequence(2);
        stop2.setArrivalTime(LocalTime.of(0, 15));
        stop2.setDepartureTime(LocalTime.of(0, 25));
        stop2.setDayOffset(1);
        trainStopRepository.save(stop2);

        // Execute JPQL DTO projection
        List<TrainSearchResponseDTO> results =
                trainStopRepository.findTrainStopsBetweenStations("STA", "KTE");

        assertNotNull(results);
        assertEquals(1, results.size());

        TrainSearchResponseDTO dto = results.get(0);
        assertEquals(train.getTrainId(), dto.trainId());
        assertEquals("11072", dto.trainNumber());
        assertEquals("Kamayani Express", dto.trainName());

        assertEquals("STA", dto.fromStationCode());
        assertEquals("SATNA", dto.fromStationName());
        assertEquals(LocalTime.of(22, 40), dto.fromArrivalTime());
        assertEquals(LocalTime.of(22, 45), dto.fromDepartureTime());
        assertEquals(0, dto.fromDayOffset());

        assertEquals("KTE", dto.toStationCode());
        assertEquals("KATNI", dto.toStationName());
        assertEquals(LocalTime.of(0, 15), dto.toArrivalTime());
        assertEquals(LocalTime.of(0, 25), dto.toDepartureTime());
        assertEquals(1, dto.toDayOffset());
    }

    @Test
    @DisplayName("Inactive trains are filtered out by DTO projection query")
    void verifyInactiveTrainsAreFiltered() {
        Train inactiveTrain = new Train();
        inactiveTrain.setTrainNumber("99999");
        inactiveTrain.setTrainName("Cancelled Train");
        inactiveTrain.setStatus("INACTIVE");
        inactiveTrain = trainRepository.save(inactiveTrain);

        TrainRoute route = new TrainRoute();
        route.setTrain(inactiveTrain);
        route.setRouteName("STA-KTE Inactive");
        route = trainRouteRepository.save(route);

        TrainStop stop1 = new TrainStop();
        stop1.setTrainRoute(route);
        stop1.setStation(staStation);
        stop1.setStopSequence(1);
        stop1.setDepartureTime(LocalTime.of(10, 0));
        stop1.setDayOffset(0);
        trainStopRepository.save(stop1);

        TrainStop stop2 = new TrainStop();
        stop2.setTrainRoute(route);
        stop2.setStation(kteStation);
        stop2.setStopSequence(2);
        stop2.setArrivalTime(LocalTime.of(12, 0));
        stop2.setDayOffset(0);
        trainStopRepository.save(stop2);

        List<TrainSearchResponseDTO> results =
                trainStopRepository.findTrainStopsBetweenStations("STA", "KTE");
        assertTrue(results.isEmpty(), "Inactive trains must be excluded from search results");
    }

    @Test
    @DisplayName("Reverse direction stops are excluded (stopSequence ordering rule)")
    void verifyReverseDirectionExcluded() {
        Train train = new Train();
        train.setTrainNumber("12142");
        train.setTrainName("Express");
        train.setStatus("ACTIVE");
        train = trainRepository.save(train);

        TrainRoute route = new TrainRoute();
        route.setTrain(train);
        route.setRouteName("KTE-STA Route");
        route = trainRouteRepository.save(route);

        // KTE is sequence 1, STA is sequence 2 (train goes from Katni to Satna)
        TrainStop stop1 = new TrainStop();
        stop1.setTrainRoute(route);
        stop1.setStation(kteStation);
        stop1.setStopSequence(1);
        stop1.setDepartureTime(LocalTime.of(8, 0));
        stop1.setDayOffset(0);
        trainStopRepository.save(stop1);

        TrainStop stop2 = new TrainStop();
        stop2.setTrainRoute(route);
        stop2.setStation(staStation);
        stop2.setStopSequence(2);
        stop2.setArrivalTime(LocalTime.of(9, 30));
        stop2.setDayOffset(0);
        trainStopRepository.save(stop2);

        // Searching STA -> KTE should return nothing because train travels in opposite direction
        List<TrainSearchResponseDTO> results =
                trainStopRepository.findTrainStopsBetweenStations("STA", "KTE");
        assertTrue(results.isEmpty(), "Stops where fromStop sequence >= toStop sequence must be excluded");
    }

    @Test
    @DisplayName("Results are ordered alphabetically by trainNumber ASC")
    void verifyResultsOrderingByTrainNumber() {
        createTwoStopRoute("12301", "Howrah Rajdhani", hwhStation, ndlsStation);
        createTwoStopRoute("12273", "Duronto Express", hwhStation, ndlsStation);
        createTwoStopRoute("12305", "Rajdhani Express", hwhStation, ndlsStation);

        List<TrainSearchResponseDTO> results =
                trainStopRepository.findTrainStopsBetweenStations("HWH", "NDLS");

        assertEquals(3, results.size());
        assertEquals("12273", results.get(0).trainNumber());
        assertEquals("12301", results.get(1).trainNumber());
        assertEquals("12305", results.get(2).trainNumber());
    }

    private Station createStation(String code, String name) {
        Station s = new Station();
        s.setStationCode(code);
        s.setStationName(name);
        return stationRepository.save(s);
    }

    private void createTwoStopRoute(String trainNumber, String trainName, Station from, Station to) {
        Train t = new Train();
        t.setTrainNumber(trainNumber);
        t.setTrainName(trainName);
        t.setStatus("ACTIVE");
        t = trainRepository.save(t);

        TrainRoute r = new TrainRoute();
        r.setTrain(t);
        r.setRouteName(trainName + " Route");
        r = trainRouteRepository.save(r);

        TrainStop s1 = new TrainStop();
        s1.setTrainRoute(r);
        s1.setStation(from);
        s1.setStopSequence(1);
        s1.setDepartureTime(LocalTime.of(12, 0));
        s1.setDayOffset(0);
        trainStopRepository.save(s1);

        TrainStop s2 = new TrainStop();
        s2.setTrainRoute(r);
        s2.setStation(to);
        s2.setStopSequence(2);
        s2.setArrivalTime(LocalTime.of(20, 0));
        s2.setDayOffset(0);
        trainStopRepository.save(s2);
    }
}
