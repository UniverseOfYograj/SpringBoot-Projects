package in.yograj.learnspringboot.railway_booking;

import in.yograj.learnspringboot.railway_booking.dto.CreateBookingRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.CreateTrainRouteRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.JourneyRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainStopRequestDTO;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.repository.*;
import in.yograj.learnspringboot.railway_booking.service.BookingService;
import in.yograj.learnspringboot.railway_booking.service.JourneyService;
import in.yograj.learnspringboot.railway_booking.service.SeatInventoryService;
import in.yograj.learnspringboot.railway_booking.service.TrainRouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:railway-segment-tests;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RailwaySegmentAllocationTests {

    @Autowired private TrainRepository trainRepository;
    @Autowired private StationRepository stationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JourneySeatInventoryRepository inventoryRepository;
    @Autowired private JourneyStopRepository journeyStopRepository;
    @Autowired private SeatAllocationRepository allocationRepository;
    @Autowired private TrainRouteService trainRouteService;
    @Autowired private JourneyService journeyService;
    @Autowired private SeatInventoryService seatInventoryService;
    @Autowired private BookingService bookingService;

    @Test
    void datedRouteSupportsOvernightSearchSegmentsAndSerializedSeatAllocation() throws Exception {
        Train train = new Train();
        train.setTrainNumber("T" + UUID.randomUUID().toString().substring(0, 8));
        train.setTrainName("Segment Test");
        train.setStatus("ACTIVE");
        train = trainRepository.save(train);

        List<Station> stations = List.of(
                station("SEG" + UUID.randomUUID().toString().substring(0, 6), "Indore"),
                station("SEG" + UUID.randomUUID().toString().substring(0, 6), "Dewas"),
                station("SEG" + UUID.randomUUID().toString().substring(0, 6), "Ujjain"),
                station("SEG" + UUID.randomUUID().toString().substring(0, 6), "Ratlam")
        );
        stationRepository.saveAll(stations);

        var route = trainRouteService.createRoute(train.getTrainId(),
                new CreateTrainRouteRequestDTO("overnight-" + UUID.randomUUID(),
                        List.of(
                                new TrainStopRequestDTO(stations.get(0).getStationId(), 1, null,
                                        LocalTime.of(23, 30), 0),
                                new TrainStopRequestDTO(stations.get(1).getStationId(), 2,
                                        LocalTime.of(0, 30), LocalTime.of(0, 35), 1),
                                new TrainStopRequestDTO(stations.get(2).getStationId(), 3,
                                        LocalTime.of(1, 30), LocalTime.of(1, 35), 1),
                                new TrainStopRequestDTO(stations.get(3).getStationId(), 4,
                                        LocalTime.of(5, 30), null, 1)
                        )));
        assertEquals(4, route.stops().size());

        LocalDate serviceDate = LocalDate.now().plusDays(5);
        var journey = journeyService.createJourney(new JourneyRequestDTO(
                train.getTrainId(), null, null, serviceDate, null, null, route.trainRouteId()));
        List<JourneyStop> runStops = journeyStopRepository
                .findByJourney_JourneyIdOrderByStopSequence(journey.journeyId());
        assertEquals(LocalDateTime.of(serviceDate.plusDays(1), LocalTime.of(5, 30)),
                runStops.get(3).getArrivalAt());

        assertEquals(1, journeyService.searchJourneys(
                stations.get(0).getStationId(), stations.get(2).getStationId(), serviceDate).size());
        assertTrue(journeyService.searchJourneys(
                stations.get(2).getStationId(), stations.get(0).getStationId(), serviceDate).isEmpty());

        List<JourneySeatInventory> inventory = inventoryRepository
                .findByJourney_JourneyId(journey.journeyId());
        assertTrue(inventory.size() >= 4);
        JourneySeatInventory reusableSeat = inventory.get(0);
        User owner = user("segment-owner");
        User other = user("segment-other");

        seatInventoryService.holdSeat(journey.journeyId(), reusableSeat.getSeat().getSeatId(), 5,
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId(), owner.getUsername());
        assertDoesNotThrow(() -> seatInventoryService.holdSeat(
                journey.journeyId(), reusableSeat.getSeat().getSeatId(), 5,
                runStops.get(2).getJourneyStopId(), runStops.get(3).getJourneyStopId(), other.getUsername()));
        assertThrows(BadRequestException.class, () -> seatInventoryService.holdSeat(
                journey.journeyId(), reusableSeat.getSeat().getSeatId(), 5,
                runStops.get(1).getJourneyStopId(), runStops.get(3).getJourneyStopId(), other.getUsername()));

        SeatAllocation expiring = allocationRepository
                .findByInventory_InventoryId(reusableSeat.getInventoryId()).get(0);
        expiring.setHoldExpiresAt(LocalDateTime.now().minusSeconds(1));
        allocationRepository.save(expiring);
        assertDoesNotThrow(() -> seatInventoryService.holdSeat(
                journey.journeyId(), reusableSeat.getSeat().getSeatId(), 5,
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId(), other.getUsername()));

        JourneySeatInventory concurrentSeat = inventory.get(1);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> one = pool.submit(() -> tryHold(
                    start, journey.journeyId(), concurrentSeat, runStops, "race-one"));
            Future<Boolean> two = pool.submit(() -> tryHold(
                    start, journey.journeyId(), concurrentSeat, runStops, "race-two"));
            start.countDown();
            assertNotEquals(one.get(), two.get(), "Only one concurrent overlapping hold may succeed");
        } finally {
            pool.shutdownNow();
        }

        JourneySeatInventory bookingSeat = inventory.get(2);
        User bookingUser = user("booking-owner");
        seatInventoryService.holdSeat(journey.journeyId(), bookingSeat.getSeat().getSeatId(), 5,
                runStops.get(0).getJourneyStopId(), runStops.get(3).getJourneyStopId(), bookingUser.getUsername());
        var booking = bookingService.createBooking(new CreateBookingRequestDTO(
                journey.journeyId(), bookingSeat.getSeat().getSeatId(),
                runStops.get(0).getJourneyStopId(), runStops.get(3).getJourneyStopId()),
                bookingUser.getUsername());
        assertEquals("PENDING_PAYMENT", booking.bookingStatus());
        assertEquals("AVAILABLE", bookingSeat.getStatus());
        assertEquals("HOLD", allocationRepository
                .findByInventory_InventoryId(bookingSeat.getInventoryId()).get(0).getStatus());
    }

    private boolean tryHold(CountDownLatch start, Long journeyId, JourneySeatInventory inventory,
                            List<JourneyStop> stops, String username) throws Exception {
        start.await();
        try {
            seatInventoryService.holdSeat(journeyId, inventory.getSeat().getSeatId(), 5,
                    stops.get(0).getJourneyStopId(), stops.get(2).getJourneyStopId(), user(username).getUsername());
            return true;
        } catch (BadRequestException expected) {
            return false;
        }
    }

    private Station station(String code, String name) {
        Station station = new Station();
        station.setStationCode(code);
        station.setStationName(name);
        station.setCity(name);
        station.setState("Test State");
        return station;
    }

    private User user(String prefix) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername(username);
        user.setFullName(prefix);
        user.setEmail(username + "@example.test");
        user.setPassword("not-used-by-this-service-test");
        user.setStatus("ACTIVE");
        user.setRole("ROLE_USER");
        return userRepository.save(user);
    }
}
