package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.*;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.*;
import in.yograj.learnspringboot.railway_booking.service.BookingService;
import in.yograj.learnspringboot.railway_booking.service.FareCalculationService;
import in.yograj.learnspringboot.railway_booking.service.SeatInventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:stage6-controllers-tests;DB_CLOSE_DELAY=-1",
        "DB_USERNAME=sa",
        "DB_PASSWORD=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:stage6-controllers-tests;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length",
        "railway.seat-hold-reaper.enabled=false",
        "railway.outbox.kafka.enabled=false"
})
@Transactional
class Stage6ControllerIntegrationTests {

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    @Autowired
    private FareController fareController;

    @Autowired
    private SeatInventoryController seatInventoryController;

    @Autowired
    private BookingController bookingController;

    @Autowired
    private FareCalculationService fareCalculationService;

    @Autowired
    private SeatInventoryService seatInventoryService;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StationRepository stationRepository;

    @Autowired
    private TrainRepository trainRepository;

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

    private User primaryUser;
    private Journey testJourney;
    private JourneyStop hwhStop;
    private JourneyStop ndlsStop;
    private Coach b1Coach;
    private Coach s1Coach;
    private Seat seatB1_1;
    private Seat seatB1_2;
    private Seat seatS1_1;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        primaryUser = new User();
        primaryUser.setUsername("testuser6");
        primaryUser.setFullName("Aarav Sharma");
        primaryUser.setEmail("aarav6@example.com");
        primaryUser.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyz123456");
        primaryUser = userRepository.save(primaryUser);

        auth = new UsernamePasswordAuthenticationToken(
                primaryUser.getUsername(),
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        Station hwh = new Station();
        hwh.setStationCode("HWH");
        hwh.setStationName("Howrah Junction");
        hwh = stationRepository.save(hwh);

        Station ndls = new Station();
        ndls.setStationCode("NDLS");
        ndls.setStationName("New Delhi");
        ndls = stationRepository.save(ndls);

        Train train = new Train();
        train.setTrainNumber("12301");
        train.setTrainName("Howrah Rajdhani Express");
        train = trainRepository.save(train);

        b1Coach = new Coach();
        b1Coach.setTrain(train);
        b1Coach.setCoachNumber("B1");
        b1Coach.setCoachType("3A");
        b1Coach.setSeatCapacity(64);
        b1Coach = coachRepository.save(b1Coach);

        s1Coach = new Coach();
        s1Coach.setTrain(train);
        s1Coach.setCoachNumber("S1");
        s1Coach.setCoachType("SL");
        s1Coach.setSeatCapacity(72);
        s1Coach = coachRepository.save(s1Coach);

        seatB1_1 = new Seat();
        seatB1_1.setCoach(b1Coach);
        seatB1_1.setSeatNumber(1);
        seatB1_1.setSeatType("LOWER");
        seatB1_1 = seatRepository.save(seatB1_1);

        seatB1_2 = new Seat();
        seatB1_2.setCoach(b1Coach);
        seatB1_2.setSeatNumber(2);
        seatB1_2.setSeatType("UPPER");
        seatB1_2 = seatRepository.save(seatB1_2);

        seatS1_1 = new Seat();
        seatS1_1.setCoach(s1Coach);
        seatS1_1.setSeatNumber(1);
        seatS1_1.setSeatType("LOWER");
        seatS1_1 = seatRepository.save(seatS1_1);

        TrainRoute route = new TrainRoute();
        route.setTrain(train);
        route.setRouteName("HWH-NDLS Line");
        route = trainRouteRepository.save(route);

        TrainStop ts1 = new TrainStop();
        ts1.setTrainRoute(route);
        ts1.setStation(hwh);
        ts1.setStopSequence(1);
        ts1.setDepartureTime(LocalTime.of(16, 55));
        ts1.setDayOffset(0);
        ts1.setDistanceFromOriginKm(BigDecimal.ZERO);
        trainStopRepository.save(ts1);

        TrainStop ts2 = new TrainStop();
        ts2.setTrainRoute(route);
        ts2.setStation(ndls);
        ts2.setStopSequence(2);
        ts2.setArrivalTime(LocalTime.of(10, 5));
        ts2.setDayOffset(1);
        ts2.setDistanceFromOriginKm(BigDecimal.valueOf(1447));
        trainStopRepository.save(ts2);

        testJourney = new Journey();
        testJourney.setTrain(train);
        testJourney.setTrainRoute(route);
        testJourney.setFromStation(hwh);
        testJourney.setToStation(ndls);
        testJourney.setJourneyDate(LocalDate.of(2026, 12, 1));
        testJourney.setDepartureTime(LocalTime.of(16, 55));
        testJourney.setArrivalTime(LocalTime.of(10, 5));
        testJourney = journeyRepository.save(testJourney);

        hwhStop = new JourneyStop();
        hwhStop.setJourney(testJourney);
        hwhStop.setStation(hwh);
        hwhStop.setStopSequence(1);
        hwhStop.setDepartureAt(LocalDateTime.of(2026, 12, 1, 16, 55));
        hwhStop.setDistanceFromOriginKm(BigDecimal.ZERO);
        hwhStop = journeyStopRepository.save(hwhStop);

        ndlsStop = new JourneyStop();
        ndlsStop.setJourney(testJourney);
        ndlsStop.setStation(ndls);
        ndlsStop.setStopSequence(2);
        ndlsStop.setArrivalAt(LocalDateTime.of(2026, 12, 2, 10, 5));
        ndlsStop.setDistanceFromOriginKm(BigDecimal.valueOf(1447));
        ndlsStop = journeyStopRepository.save(ndlsStop);

        createInventory(testJourney, b1Coach, seatB1_1);
        createInventory(testJourney, b1Coach, seatB1_2);
        createInventory(testJourney, s1Coach, seatS1_1);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private JourneySeatInventory createInventory(Journey journey, Coach coach, Seat seat) {
        JourneySeatInventory inv = new JourneySeatInventory();
        inv.setJourney(journey);
        inv.setCoach(coach);
        inv.setSeat(seat);
        inv.setStatus("AVAILABLE");
        return inventoryRepository.save(inv);
    }

    @Test
    @DisplayName("FareController GET /api/fare/quote calculates dynamic fare with per-passenger and total breakdown")
    void fareController_getFareQuote_calculatesDynamicFare() {
        FareQuoteResponseDTO quote = fareController.getFareQuote(
                testJourney.getJourneyId(), "3A", 2,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId());

        assertThat(quote).isNotNull();
        assertThat(quote.journeyId()).isEqualTo(testJourney.getJourneyId());
        assertThat(quote.travelClass()).isEqualTo("3A");
        assertThat(quote.passengerCount()).isEqualTo(2);
        assertThat(quote.distanceKm()).isEqualTo(1447);

        // Per passenger and total amounts must be positive and dynamic
        assertThat(quote.perPassengerFare()).isNotNull();
        assertThat(quote.bookingTotal()).isNotNull();
        assertThat(quote.bookingTotal()).isGreaterThan(quote.perPassengerFare().total());
        // 3A includes GST (> 0)
        assertThat(quote.perPassengerFare().tax()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("FareController POST /api/fare/quote calculates dynamic fare for Sleeper class")
    void fareController_postFareQuote_calculatesSleeperFareWithoutGst() {
        FareQuoteRequestDTO request = new FareQuoteRequestDTO(
                testJourney.getJourneyId(), "SL", 1,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId());

        FareQuoteResponseDTO quote = fareController.postFareQuote(request);

        assertThat(quote).isNotNull();
        assertThat(quote.travelClass()).isEqualTo("SL");
        assertThat(quote.distanceKm()).isEqualTo(1447);
        // Sleeper has 0 GST
        assertThat(quote.perPassengerFare().tax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(quote.bookingTotal()).isGreaterThan(BigDecimal.valueOf(500));
    }

    @Test
    @DisplayName("SeatInventoryController GET /{journeyId}/classes returns travel class availability and pricing")
    void seatInventoryController_getJourneyClasses_returnsClassesWithDynamicFare() {
        List<TravelClassAvailabilityDTO> classes = seatInventoryController.getJourneyClasses(
                testJourney.getJourneyId(), hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId());

        assertThat(classes).isNotEmpty();
        TravelClassAvailabilityDTO threeTier = classes.stream()
                .filter(c -> "3A".equalsIgnoreCase(c.classCode()))
                .findFirst()
                .orElse(null);
        assertThat(threeTier).isNotNull();
        assertThat(threeTier.availableSeats()).isEqualTo(2);
        assertThat(threeTier.baseFare()).isNotNull();
        assertThat(threeTier.baseFare()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("SeatInventoryController GET /{journeyId}/seats filters by travelClass and coachNumber")
    void seatInventoryController_getJourneySeats_filtersByClassAndCoach() {
        List<SeatAvailabilityResponseDTO> b1Seats = seatInventoryController.getJourneySeats(
                testJourney.getJourneyId(), hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(), "3A", "B1");

        assertThat(b1Seats).hasSize(2);
        assertThat(b1Seats).allMatch(s -> "B1".equals(s.coachNumber()) && "3A".equals(s.coachType()));
    }

    @Test
    @DisplayName("SeatInventoryController POST /{journeyId}/seats/hold-batch holds multiple seats and rejects duplicates")
    void seatInventoryController_holdSeatsBatch_holdsMultipleSeatsAndRejectsDuplicates() {
        // 1. Valid batch hold
        BatchHoldSeatRequestDTO validBatch = new BatchHoldSeatRequestDTO(
                List.of(seatB1_1.getSeatId(), seatB1_2.getSeatId()), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", BookingMode.GENERAL);

        ResponseEntity<List<SeatAvailabilityResponseDTO>> holdResponse =
                seatInventoryController.holdSeats(testJourney.getJourneyId(), validBatch, auth);

        assertThat(holdResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(holdResponse.getBody()).hasSize(2);
        assertThat(holdResponse.getBody()).allMatch(s -> "HOLD".equals(s.status()));

        // 2. Duplicate seats in batch hold must be rejected
        BatchHoldSeatRequestDTO duplicateBatch = new BatchHoldSeatRequestDTO(
                List.of(seatB1_1.getSeatId(), seatB1_1.getSeatId()), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", BookingMode.GENERAL);

        assertThatThrownBy(() -> seatInventoryController.holdSeats(testJourney.getJourneyId(), duplicateBatch, auth))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Duplicate seats");
    }

    @Test
    @DisplayName("BookingController creates multi-passenger booking and retrieves it via 10-digit numeric PNR")
    void bookingController_createAndRetrieveByPnr_succeeds() {
        // First hold the 2 seats
        seatInventoryService.holdSeatsBatch(
                testJourney.getJourneyId(),
                List.of(seatB1_1.getSeatId(), seatB1_2.getSeatId()), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", primaryUser.getUsername());

        List<PassengerRequestDTO> passengers = List.of(
                new PassengerRequestDTO("Aarav Sharma", 29, "MALE", seatB1_1.getSeatId(), "LOWER"),
                new PassengerRequestDTO("Neha Sharma", 27, "FEMALE", seatB1_2.getSeatId(), "UPPER")
        );

        CreateBookingRequestDTO createReq = new CreateBookingRequestDTO(
                testJourney.getJourneyId(), null,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", passengers);

        ResponseEntity<BookingResponseDTO> createResponse =
                bookingController.createBooking(createReq, auth, null);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        BookingResponseDTO created = createResponse.getBody();
        assertThat(created).isNotNull();
        assertThat(created.pnr()).isNotNull().hasSize(10).matches("\\d{10}");
        assertThat(created.passengers()).hasSize(2);
        assertThat(created.fareBreakdown()).isNotNull();
        assertThat(created.distanceKm()).isEqualTo(1447);

        // Retrieve by numeric PNR
        ResponseEntity<BookingDetailResponseDTO> pnrResponse =
                bookingController.getBookingByPnr(created.pnr(), auth);

        assertThat(pnrResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        BookingDetailResponseDTO retrieved = pnrResponse.getBody();
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.pnr()).isEqualTo(created.pnr());
        assertThat(retrieved.passengers()).hasSize(2);
        assertThat(retrieved.fareBreakdown()).isNotNull();
        assertThat(retrieved.distanceKm()).isEqualTo(1447);
    }
}
