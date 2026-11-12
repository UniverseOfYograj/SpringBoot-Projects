package in.yograj.learnspringboot.railway_booking;

import in.yograj.learnspringboot.railway_booking.dto.*;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.*;
import in.yograj.learnspringboot.railway_booking.service.BookingService;
import in.yograj.learnspringboot.railway_booking.service.SeatInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:class-aware-booking-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length",
        "railway.seat-hold-reaper.enabled=false"
})
class ClassAwareBookingTests {

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    @Autowired
    private SeatInventoryService seatInventoryService;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TrainRepository trainRepository;

    @Autowired
    private StationRepository stationRepository;

    @Autowired
    private CoachRepository coachRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private TrainRouteRepository trainRouteRepository;

    @Autowired
    private TrainStopRepository trainStopRepository;

    @Autowired
    private JourneyRepository journeyRepository;

    @Autowired
    private JourneyStopRepository journeyStopRepository;

    @Autowired
    private JourneySeatInventoryRepository inventoryRepository;

    @Autowired
    private SeatAllocationRepository allocationRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    private User testUser;
    private Journey testJourney;
    private JourneyStop hwhStop;
    private JourneyStop ndlsStop;
    private Coach s1Coach;
    private Coach b1Coach;
    private Coach a1Coach;
    private Seat s1Seat1;
    private Seat b1Seat1;
    private Seat a1Seat1;

    @BeforeEach
    void setUp() {
        paymentTransactionRepository.deleteAll();
        bookingRepository.deleteAll();
        allocationRepository.deleteAll();
        inventoryRepository.deleteAll();
        journeyStopRepository.deleteAll();
        journeyRepository.deleteAll();
        trainStopRepository.deleteAll();
        trainRouteRepository.deleteAll();
        seatRepository.deleteAll();
        coachRepository.deleteAll();
        trainRepository.deleteAll();
        stationRepository.deleteAll();
        userRepository.deleteAll();

        // 1. Create User
        testUser = new User();
        testUser.setUsername("traveler1");
        testUser.setFullName("Priya Sharma");
        testUser.setEmail("priya@example.com");
        testUser.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyz123456");
        testUser.setRole("ROLE_USER");
        testUser = userRepository.save(testUser);

        // 2. Create Train
        Train train = new Train();
        train.setTrainNumber("12301");
        train.setTrainName("Howrah Rajdhani Express");
        train.setStatus("ACTIVE");
        train = trainRepository.save(train);

        // 3. Create Stations
        Station hwh = new Station();
        hwh.setStationCode("HWH");
        hwh.setStationName("Howrah Junction");
        hwh = stationRepository.save(hwh);

        Station cnb = new Station();
        cnb.setStationCode("CNB");
        cnb.setStationName("Kanpur Central");
        cnb = stationRepository.save(cnb);

        Station ndls = new Station();
        ndls.setStationCode("NDLS");
        ndls.setStationName("New Delhi");
        ndls = stationRepository.save(ndls);

        // 4. Create Route & Stops
        TrainRoute route = new TrainRoute();
        route.setTrain(train);
        route.setRouteName("HWH-NDLS Corridor");
        route = trainRouteRepository.save(route);

        TrainStop ts1 = new TrainStop();
        ts1.setTrainRoute(route);
        ts1.setStation(hwh);
        ts1.setStopSequence(1);
        ts1.setDepartureTime(LocalTime.of(16, 55));
        ts1.setDayOffset(0);
        trainStopRepository.save(ts1);

        TrainStop ts2 = new TrainStop();
        ts2.setTrainRoute(route);
        ts2.setStation(cnb);
        ts2.setStopSequence(2);
        ts2.setArrivalTime(LocalTime.of(4, 45));
        ts2.setDepartureTime(LocalTime.of(4, 50));
        ts2.setDayOffset(1);
        trainStopRepository.save(ts2);

        TrainStop ts3 = new TrainStop();
        ts3.setTrainRoute(route);
        ts3.setStation(ndls);
        ts3.setStopSequence(3);
        ts3.setArrivalTime(LocalTime.of(9, 55));
        ts3.setDayOffset(1);
        trainStopRepository.save(ts3);

        // 5. Create Journey & Journey Stops
        LocalDate journeyDate = LocalDate.parse("2026-12-01");
        testJourney = new Journey();
        testJourney.setTrain(train);
        testJourney.setTrainRoute(route);
        testJourney.setFromStation(hwh);
        testJourney.setToStation(ndls);
        testJourney.setJourneyDate(journeyDate);
        testJourney.setDepartureTime(LocalTime.of(16, 55));
        testJourney.setArrivalTime(LocalTime.of(9, 55));
        testJourney = journeyRepository.save(testJourney);

        hwhStop = new JourneyStop();
        hwhStop.setJourney(testJourney);
        hwhStop.setStation(hwh);
        hwhStop.setStopSequence(1);
        hwhStop.setDepartureAt(LocalDateTime.of(journeyDate, LocalTime.of(16, 55)));
        hwhStop = journeyStopRepository.save(hwhStop);

        JourneyStop cnbStop = new JourneyStop();
        cnbStop.setJourney(testJourney);
        cnbStop.setStation(cnb);
        cnbStop.setStopSequence(2);
        cnbStop.setArrivalAt(LocalDateTime.of(journeyDate.plusDays(1), LocalTime.of(4, 45)));
        cnbStop.setDepartureAt(LocalDateTime.of(journeyDate.plusDays(1), LocalTime.of(4, 50)));
        journeyStopRepository.save(cnbStop);

        ndlsStop = new JourneyStop();
        ndlsStop.setJourney(testJourney);
        ndlsStop.setStation(ndls);
        ndlsStop.setStopSequence(3);
        ndlsStop.setArrivalAt(LocalDateTime.of(journeyDate.plusDays(1), LocalTime.of(9, 55)));
        ndlsStop = journeyStopRepository.save(ndlsStop);

        // 6. Create Coaches matching Phase 17.15 Demo Configuration
        s1Coach = new Coach();
        s1Coach.setTrain(train);
        s1Coach.setCoachNumber("S1");
        s1Coach.setCoachType("SL");
        s1Coach.setSeatCapacity(6);
        s1Coach.setStatus("ACTIVE");
        s1Coach = coachRepository.save(s1Coach);

        b1Coach = new Coach();
        b1Coach.setTrain(train);
        b1Coach.setCoachNumber("B1");
        b1Coach.setCoachType("3A");
        b1Coach.setSeatCapacity(6);
        b1Coach.setStatus("ACTIVE");
        b1Coach = coachRepository.save(b1Coach);

        a1Coach = new Coach();
        a1Coach.setTrain(train);
        a1Coach.setCoachNumber("A1");
        a1Coach.setCoachType("2A");
        a1Coach.setSeatCapacity(4);
        a1Coach.setStatus("ACTIVE");
        a1Coach = coachRepository.save(a1Coach);

        // 7. Create Seats and JourneySeatInventory for S1 (6 seats)
        String[] s1Berths = {"LOWER", "MIDDLE", "UPPER", "SIDE_LOWER", "SIDE_UPPER", "LOWER"};
        for (int i = 1; i <= 6; i++) {
            Seat s = new Seat();
            s.setCoach(s1Coach);
            s.setSeatNumber(i);
            s.setSeatType(s1Berths[i - 1]);
            s.setStatus("AVAILABLE");
            s = seatRepository.save(s);
            if (i == 1) s1Seat1 = s;

            JourneySeatInventory inv = new JourneySeatInventory();
            inv.setJourney(testJourney);
            inv.setCoach(s1Coach);
            inv.setSeat(s);
            inv.setStatus("AVAILABLE");
            inv.setUpdatedAt(LocalDateTime.now());
            inventoryRepository.save(inv);
        }

        // 8. Create Seats and JourneySeatInventory for B1 (6 seats)
        String[] b1Berths = {"LOWER", "MIDDLE", "UPPER", "SIDE_LOWER", "SIDE_UPPER", "LOWER"};
        for (int i = 1; i <= 6; i++) {
            Seat s = new Seat();
            s.setCoach(b1Coach);
            s.setSeatNumber(i);
            s.setSeatType(b1Berths[i - 1]);
            s.setStatus("AVAILABLE");
            s = seatRepository.save(s);
            if (i == 1) b1Seat1 = s;

            JourneySeatInventory inv = new JourneySeatInventory();
            inv.setJourney(testJourney);
            inv.setCoach(b1Coach);
            inv.setSeat(s);
            inv.setStatus("AVAILABLE");
            inv.setUpdatedAt(LocalDateTime.now());
            inventoryRepository.save(inv);
        }

        // 9. Create Seats and JourneySeatInventory for A1 (4 seats)
        String[] a1Berths = {"LOWER", "UPPER", "SIDE_LOWER", "SIDE_UPPER"};
        for (int i = 1; i <= 4; i++) {
            Seat s = new Seat();
            s.setCoach(a1Coach);
            s.setSeatNumber(i);
            s.setSeatType(a1Berths[i - 1]);
            s.setStatus("AVAILABLE");
            s = seatRepository.save(s);
            if (i == 1) a1Seat1 = s;

            JourneySeatInventory inv = new JourneySeatInventory();
            inv.setJourney(testJourney);
            inv.setCoach(a1Coach);
            inv.setSeat(s);
            inv.setStatus("AVAILABLE");
            inv.setUpdatedAt(LocalDateTime.now());
            inventoryRepository.save(inv);
        }
    }

    @Test
    void getJourneyClassesAvailability_returnsGroupedClassesAndCoachesWithAccurateCounts() {
        List<TravelClassAvailabilityDTO> classes = seatInventoryService.getJourneyClassesAvailability(
                testJourney.getJourneyId(), hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId());

        assertNotNull(classes);
        assertEquals(3, classes.size(), "Expected 3 travel classes (2A, 3A, SL)");

        // 1. Verify 2A
        TravelClassAvailabilityDTO class2A = classes.stream()
                .filter(c -> "2A".equalsIgnoreCase(c.classCode()))
                .findFirst().orElseThrow();
        assertEquals("AC 2 Tier", class2A.className());
        assertEquals(4, class2A.totalSeats());
        assertEquals(4, class2A.availableSeats());
        assertEquals(0, class2A.heldSeats());
        assertEquals(0, class2A.bookedSeats());
        assertEquals(1, class2A.coaches().size());
        CoachAvailabilityDTO a1CoachDto = class2A.coaches().get(0);
        assertEquals("A1", a1CoachDto.coachNumber());
        assertEquals(4, a1CoachDto.seats().size());

        // 2. Verify 3A
        TravelClassAvailabilityDTO class3A = classes.stream()
                .filter(c -> "3A".equalsIgnoreCase(c.classCode()))
                .findFirst().orElseThrow();
        assertEquals("AC 3 Tier", class3A.className());
        assertEquals(6, class3A.totalSeats());
        assertEquals(6, class3A.availableSeats());
        assertEquals(1, class3A.coaches().size());
        assertEquals("B1", class3A.coaches().get(0).coachNumber());

        // 3. Verify SL
        TravelClassAvailabilityDTO classSL = classes.stream()
                .filter(c -> "SL".equalsIgnoreCase(c.classCode()))
                .findFirst().orElseThrow();
        assertEquals("Sleeper", classSL.className());
        assertEquals(6, classSL.totalSeats());
        assertEquals(6, classSL.availableSeats());
        assertEquals(1, classSL.coaches().size());
        assertEquals("S1", classSL.coaches().get(0).coachNumber());
    }

    @Test
    void seatFiltering_byClassAndCoach_returnsOnlyMatchingSeats() {
        // Query by Class SL
        List<SeatAvailabilityResponseDTO> slSeats = seatInventoryService.getJourneySeatAvailability(
                testJourney.getJourneyId(), hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "SL", null);
        assertEquals(6, slSeats.size());
        assertTrue(slSeats.stream().allMatch(s -> "SL".equalsIgnoreCase(s.coachType())));
        assertTrue(slSeats.stream().allMatch(s -> "S1".equalsIgnoreCase(s.coachNumber())));

        // Query by Class 3A
        List<SeatAvailabilityResponseDTO> threeAcSeats = seatInventoryService.getJourneySeatAvailability(
                testJourney.getJourneyId(), hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", null);
        assertEquals(6, threeAcSeats.size());
        assertTrue(threeAcSeats.stream().allMatch(s -> "3A".equalsIgnoreCase(s.coachType())));
        assertTrue(threeAcSeats.stream().allMatch(s -> "B1".equalsIgnoreCase(s.coachNumber())));

        // Query by Coach A1
        List<SeatAvailabilityResponseDTO> a1Seats = seatInventoryService.getJourneySeatAvailability(
                testJourney.getJourneyId(), hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                null, "A1");
        assertEquals(4, a1Seats.size());
        assertTrue(a1Seats.stream().allMatch(s -> "2A".equalsIgnoreCase(s.coachType())));
        assertTrue(a1Seats.stream().allMatch(s -> "A1".equalsIgnoreCase(s.coachNumber())));
    }

    @Test
    void holdSeat_withMismatchedClass_rejectsWithBadRequestException() {
        // Attempt to hold S1 (SL) seat with travelClass="3A"
        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                seatInventoryService.holdSeat(
                        testJourney.getJourneyId(),
                        s1Seat1.getSeatId(),
                        10,
                        hwhStop.getJourneyStopId(),
                        ndlsStop.getJourneyStopId(),
                        "3A",
                        (String) null,
                        testUser.getUsername()
                )
        );
        assertTrue(ex.getMessage().contains("belongs to class SL, not requested class 3A"));
    }

    @Test
    void holdSeat_withMismatchedCoach_rejectsWithBadRequestException() {
        // Attempt to hold S1 seat with coachNumber="B1"
        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                seatInventoryService.holdSeat(
                        testJourney.getJourneyId(),
                        s1Seat1.getSeatId(),
                        10,
                        hwhStop.getJourneyStopId(),
                        ndlsStop.getJourneyStopId(),
                        "SL",
                        "B1",
                        testUser.getUsername()
                )
        );
        assertTrue(ex.getMessage().contains("belongs to coach S1, not requested coach B1"));
    }

    @Test
    void createBooking_withMismatchedClass_rejectsWithBadRequestException() {
        // First hold S1 seat legitimately under SL
        SeatAvailabilityResponseDTO hold = seatInventoryService.holdSeat(
                testJourney.getJourneyId(),
                s1Seat1.getSeatId(),
                10,
                hwhStop.getJourneyStopId(),
                ndlsStop.getJourneyStopId(),
                "SL",
                "S1",
                testUser.getUsername()
        );
        assertEquals("HOLD", hold.status());

        // Then attempt to create booking sending travelClass="3A"
        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                testJourney.getJourneyId(),
                s1Seat1.getSeatId(),
                hwhStop.getJourneyStopId(),
                ndlsStop.getJourneyStopId(),
                "3A",
                null
        );

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                bookingService.createBooking(request, testUser.getUsername())
        );
        assertTrue(ex.getMessage().contains("belongs to class SL, not requested class 3A"));
    }

    @Test
    void holdAndBook_matchingClassAndCoach_succeeds() {
        // 1. Hold B1 (3A) seat
        SeatAvailabilityResponseDTO hold = seatInventoryService.holdSeat(
                testJourney.getJourneyId(),
                b1Seat1.getSeatId(),
                10,
                hwhStop.getJourneyStopId(),
                ndlsStop.getJourneyStopId(),
                "3A",
                "B1",
                testUser.getUsername()
        );
        assertNotNull(hold);
        assertEquals("HOLD", hold.status());
        assertEquals("3A", hold.coachType());
        assertEquals("B1", hold.coachNumber());

        // 2. Create Booking matching 3A and B1
        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                testJourney.getJourneyId(),
                b1Seat1.getSeatId(),
                hwhStop.getJourneyStopId(),
                ndlsStop.getJourneyStopId(),
                "3A",
                "B1"
        );
        BookingResponseDTO booking = bookingService.createBooking(request, testUser.getUsername());
        assertNotNull(booking);
        assertEquals("PENDING_PAYMENT", booking.bookingStatus());
        assertEquals("B1", booking.coachNumber());

        // 3. Verify Class Counts update
        List<TravelClassAvailabilityDTO> updatedClasses = seatInventoryService.getJourneyClassesAvailability(
                testJourney.getJourneyId(), hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId());
        TravelClassAvailabilityDTO updated3A = updatedClasses.stream()
                .filter(c -> "3A".equalsIgnoreCase(c.classCode()))
                .findFirst().orElseThrow();
        assertEquals(6, updated3A.totalSeats());
        assertEquals(5, updated3A.availableSeats(), "One seat was held, so available must be 5");
        assertEquals(1, updated3A.heldSeats(), "One seat is in HOLD status");
    }

    @Test
    void holdSeat_fromAnotherJourney_throwsResourceNotFoundException() {
        assertThrows(ResourceNotFoundException.class, () ->
                seatInventoryService.holdSeat(
                        999999L,
                        s1Seat1.getSeatId(),
                        10,
                        hwhStop.getJourneyStopId(),
                        ndlsStop.getJourneyStopId(),
                        "SL",
                        "S1",
                        testUser.getUsername()
                )
        );
    }
}
