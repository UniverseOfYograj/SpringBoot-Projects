package in.yograj.learnspringboot.railway_booking;

import in.yograj.learnspringboot.railway_booking.config.FareProperties;
import in.yograj.learnspringboot.railway_booking.dto.*;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.*;
import in.yograj.learnspringboot.railway_booking.service.BookingService;
import in.yograj.learnspringboot.railway_booking.service.DistanceResolverService;
import in.yograj.learnspringboot.railway_booking.service.FareCalculationService;
import in.yograj.learnspringboot.railway_booking.service.SeatInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class FareCalculationTests {

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    @Autowired
    private FareCalculationService fareCalculationService;

    @Autowired
    private DistanceResolverService distanceResolverService;

    @Autowired
    private FareProperties fareProperties;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private SeatInventoryService seatInventoryService;

    @Autowired
    private JourneyRepository journeyRepository;

    @Autowired
    private JourneyStopRepository journeyStopRepository;

    @Autowired
    private TrainRepository trainRepository;

    @Autowired
    private TrainRouteRepository trainRouteRepository;

    @Autowired
    private TrainStopRepository trainStopRepository;

    @Autowired
    private StationRepository stationRepository;

    @Autowired
    private CoachRepository coachRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private JourneySeatInventoryRepository inventoryRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private UserRepository userRepository;

    private User testUser;
    private Journey hwhNdlsJourney;
    private JourneyStop hwhStop;
    private JourneyStop ndlsStop;
    private Coach coachS1;
    private Coach coachB1;
    private Coach coachA1;
    private Seat seatS1;
    private Seat seatB1;
    private Seat seatA1;

    @BeforeEach
    void setUp() {
        // Create user
        testUser = new User();
        testUser.setUsername("fare_traveler");
        testUser.setFullName("Aditi Rao");
        testUser.setEmail("aditi@example.com");
        testUser.setPassword("passwordHash");
        testUser.setRole("ROLE_USER");
        testUser = userRepository.save(testUser);

        // Stations
        Station hwh = new Station();
        hwh.setStationCode("HWH");
        hwh.setStationName("Howrah Junction");
        hwh = stationRepository.save(hwh);

        Station ndls = new Station();
        ndls.setStationCode("NDLS");
        ndls.setStationName("New Delhi");
        ndls = stationRepository.save(ndls);

        // Train 12301
        Train train = new Train();
        train.setTrainNumber("12301");
        train.setTrainName("Howrah Rajdhani Express");
        train.setStatus("ACTIVE");
        train = trainRepository.save(train);

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
        ts2.setStation(ndls);
        ts2.setStopSequence(2);
        ts2.setArrivalTime(LocalTime.of(10, 5));
        ts2.setDayOffset(1);
        trainStopRepository.save(ts2);

        hwhNdlsJourney = new Journey();
        hwhNdlsJourney.setTrain(train);
        hwhNdlsJourney.setTrainRoute(route);
        hwhNdlsJourney.setFromStation(hwh);
        hwhNdlsJourney.setToStation(ndls);
        hwhNdlsJourney.setJourneyDate(LocalDate.of(2026, 12, 1));
        hwhNdlsJourney.setDepartureTime(LocalTime.of(16, 55));
        hwhNdlsJourney.setArrivalTime(LocalTime.of(10, 5));
        hwhNdlsJourney = journeyRepository.save(hwhNdlsJourney);

        hwhStop = new JourneyStop();
        hwhStop.setJourney(hwhNdlsJourney);
        hwhStop.setStation(hwh);
        hwhStop.setStopSequence(1);
        hwhStop.setDepartureAt(LocalDateTime.of(2026, 12, 1, 16, 55));
        hwhStop = journeyStopRepository.save(hwhStop);

        ndlsStop = new JourneyStop();
        ndlsStop.setJourney(hwhNdlsJourney);
        ndlsStop.setStation(ndls);
        ndlsStop.setStopSequence(2);
        ndlsStop.setArrivalAt(LocalDateTime.of(2026, 12, 2, 10, 5));
        ndlsStop = journeyStopRepository.save(ndlsStop);

        // Coaches
        coachS1 = new Coach();
        coachS1.setTrain(train);
        coachS1.setCoachNumber("S1");
        coachS1.setCoachType("SL");
        coachS1.setSeatCapacity(6);
        coachS1 = coachRepository.save(coachS1);

        coachB1 = new Coach();
        coachB1.setTrain(train);
        coachB1.setCoachNumber("B1");
        coachB1.setCoachType("3A");
        coachB1.setSeatCapacity(6);
        coachB1 = coachRepository.save(coachB1);

        coachA1 = new Coach();
        coachA1.setTrain(train);
        coachA1.setCoachNumber("A1");
        coachA1.setCoachType("2A");
        coachA1.setSeatCapacity(4);
        coachA1 = coachRepository.save(coachA1);

        // Seats & Inventory
        seatS1 = new Seat();
        seatS1.setCoach(coachS1);
        seatS1.setSeatNumber(1);
        seatS1.setSeatType("LOWER");
        seatS1 = seatRepository.save(seatS1);

        createInventory(hwhNdlsJourney, coachS1, seatS1);

        seatB1 = new Seat();
        seatB1.setCoach(coachB1);
        seatB1.setSeatNumber(7);
        seatB1.setSeatType("LOWER");
        seatB1 = seatRepository.save(seatB1);

        createInventory(hwhNdlsJourney, coachB1, seatB1);

        seatA1 = new Seat();
        seatA1.setCoach(coachA1);
        seatA1.setSeatNumber(13);
        seatA1.setSeatType("LOWER");
        seatA1 = seatRepository.save(seatA1);

        createInventory(hwhNdlsJourney, coachA1, seatA1);
    }

    private void createInventory(Journey journey, Coach coach, Seat seat) {
        JourneySeatInventory inv = new JourneySeatInventory();
        inv.setJourney(journey);
        inv.setCoach(coach);
        inv.setSeat(seat);
        inv.setStatus("AVAILABLE");
        inv.setUpdatedAt(LocalDateTime.now());
        inventoryRepository.save(inv);
    }

    @Test
    void differentTravelClasses_produceDistinctAndAccurateFares() {
        // HWH - NDLS: 1447 km
        // 1. Sleeper (SL)
        FareQuoteResponseDTO slQuote = fareCalculationService.calculateFareQuote(
                hwhNdlsJourney.getJourneyId(), "SL", 1, hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId());
        assertNotNull(slQuote);
        assertEquals(1447, slQuote.distanceKm());
        assertEquals("CONFIGURED_CORRIDOR", slQuote.distanceSource());
        assertEquals(new BigDecimal("0.45"), slQuote.ratePerKm());
        // SL Base = 1447 * 0.45 = 651.15
        assertEquals(new BigDecimal("651.15"), slQuote.perPassengerFare().baseFare());
        assertEquals(new BigDecimal("20.00"), slQuote.perPassengerFare().reservationCharge());
        assertEquals(new BigDecimal("30.00"), slQuote.perPassengerFare().superfastCharge());
        assertEquals(new BigDecimal("0.00"), slQuote.perPassengerFare().tax()); // 0% GST
        assertEquals(new BigDecimal("701.15"), slQuote.perPassengerFare().total());
        assertEquals(new BigDecimal("701.15"), slQuote.bookingTotal());

        // 2. AC 3 Tier (3A)
        FareQuoteResponseDTO threeAQuote = fareCalculationService.calculateFareQuote(
                hwhNdlsJourney.getJourneyId(), "3A", 1, hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId());
        assertEquals(new BigDecimal("1.15"), threeAQuote.ratePerKm());
        // 3A Base = 1447 * 1.15 = 1664.05
        assertEquals(new BigDecimal("1664.05"), threeAQuote.perPassengerFare().baseFare());
        assertEquals(new BigDecimal("40.00"), threeAQuote.perPassengerFare().reservationCharge());
        assertEquals(new BigDecimal("45.00"), threeAQuote.perPassengerFare().superfastCharge());
        // Subtotal = 1749.05, GST (5%) = 87.45
        assertEquals(new BigDecimal("87.45"), threeAQuote.perPassengerFare().tax());
        assertEquals(new BigDecimal("1836.50"), threeAQuote.perPassengerFare().total());
        assertEquals(new BigDecimal("1836.50"), threeAQuote.bookingTotal());

        // 3. AC 2 Tier (2A)
        FareQuoteResponseDTO twoAQuote = fareCalculationService.calculateFareQuote(
                hwhNdlsJourney.getJourneyId(), "2A", 1, hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId());
        assertEquals(new BigDecimal("1.65"), twoAQuote.ratePerKm());
        // 2A Base = 1447 * 1.65 = 2387.55
        assertEquals(new BigDecimal("2387.55"), twoAQuote.perPassengerFare().baseFare());
        assertEquals(new BigDecimal("50.00"), twoAQuote.perPassengerFare().reservationCharge());
        assertEquals(new BigDecimal("45.00"), twoAQuote.perPassengerFare().superfastCharge());
        // Subtotal = 2482.55, GST (5%) = 124.13
        assertEquals(new BigDecimal("124.13"), twoAQuote.perPassengerFare().tax());
        assertEquals(new BigDecimal("2606.68"), twoAQuote.perPassengerFare().total());
        assertEquals(new BigDecimal("2606.68"), twoAQuote.bookingTotal());

        // Distinct prices
        assertTrue(twoAQuote.bookingTotal().compareTo(threeAQuote.bookingTotal()) > 0);
        assertTrue(threeAQuote.bookingTotal().compareTo(slQuote.bookingTotal()) > 0);
    }

    @Test
    void multiplePassengerCounts_scalePerPassengerFareLinearly() {
        FareQuoteResponseDTO quote1 = fareCalculationService.calculateFareQuote(
                hwhNdlsJourney.getJourneyId(), "3A", 1, null, null);
        FareQuoteResponseDTO quote3 = fareCalculationService.calculateFareQuote(
                hwhNdlsJourney.getJourneyId(), "3A", 3, null, null);
        FareQuoteResponseDTO quote6 = fareCalculationService.calculateFareQuote(
                hwhNdlsJourney.getJourneyId(), "3A", 6, null, null);

        // Per passenger fare remains identical
        assertEquals(quote1.perPassengerFare().total(), quote3.perPassengerFare().total());
        assertEquals(quote1.perPassengerFare().total(), quote6.perPassengerFare().total());

        // Booking total scales exactly by passenger count
        assertEquals(quote1.perPassengerFare().total().multiply(new BigDecimal("3")), quote3.bookingTotal());
        assertEquals(quote1.perPassengerFare().total().multiply(new BigDecimal("6")), quote6.bookingTotal());
    }

    @Test
    void minimumFareRule_appliesWhenDistanceIsVeryShort() {
        // Create short journey stop with 10 km distance
        hwhStop.setDistanceFromOriginKm(new BigDecimal("0.00"));
        ndlsStop.setDistanceFromOriginKm(new BigDecimal("10.00"));
        journeyStopRepository.save(hwhStop);
        journeyStopRepository.save(ndlsStop);

        // For SL: 10 km * 0.45 = 4.50, but minimum base fare is 140.00
        FareQuoteResponseDTO quote = fareCalculationService.calculateFareQuote(
                hwhNdlsJourney.getJourneyId(), "SL", 1, hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId());

        assertEquals(10, quote.distanceKm());
        assertEquals("STOP_METRICS", quote.distanceSource());
        assertEquals(new BigDecimal("140.00"), quote.perPassengerFare().baseFare());
        assertEquals(new BigDecimal("190.00"), quote.bookingTotal()); // 140 + 20 + 30
    }

    @Test
    void invalidJourneyId_throwsResourceNotFoundException() {
        assertThrows(ResourceNotFoundException.class, () ->
                fareCalculationService.calculateFareQuote(999999L, "3A", 1, null, null));
    }

    @Test
    void invalidOrUnsupportedClass_throwsBadRequestException() {
        assertThrows(BadRequestException.class, () ->
                fareCalculationService.calculateFareQuote(hwhNdlsJourney.getJourneyId(), "INVALID_CLASS", 1, null, null));
        assertThrows(BadRequestException.class, () ->
                fareCalculationService.calculateFareQuote(hwhNdlsJourney.getJourneyId(), "", 1, null, null));
    }

    @Test
    void invalidPassengerCount_throwsBadRequestException() {
        assertThrows(BadRequestException.class, () ->
                fareCalculationService.calculateFareQuote(hwhNdlsJourney.getJourneyId(), "3A", 0, null, null));
        assertThrows(BadRequestException.class, () ->
                fareCalculationService.calculateFareQuote(hwhNdlsJourney.getJourneyId(), "3A", 7, null, null));
    }

    @Test
    void bookingCreation_ignoresClientSuppliedAmounts_andPersistsServerCalculatedFareSnapshot() {
        // Hold seat in 3A
        seatInventoryService.holdSeat(
                hwhNdlsJourney.getJourneyId(), seatB1.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", testUser.getUsername());

        List<PassengerRequestDTO> passengers = List.of(
                new PassengerRequestDTO("Aditi Rao", 28, "FEMALE", seatB1.getSeatId(), "LOWER")
        );

        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                hwhNdlsJourney.getJourneyId(), null,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", passengers
        );

        BookingResponseDTO response = bookingService.createBooking(request, testUser.getUsername());

        assertNotNull(response);
        // Server-calculated dynamic fare for 1 passenger in 3A over 1,447 km
        assertEquals(new BigDecimal("1836.50"), response.amount());
        assertEquals(1447, response.distanceKm());
        assertNotNull(response.fareBreakdown());
        assertEquals(new BigDecimal("1664.05"), response.fareBreakdown().baseFare());
        assertEquals(new BigDecimal("40.00"), response.fareBreakdown().reservationCharge());
        assertEquals(new BigDecimal("45.00"), response.fareBreakdown().superfastCharge());
        assertEquals(new BigDecimal("87.45"), response.fareBreakdown().tax());
        assertEquals(new BigDecimal("1836.50"), response.fareBreakdown().total());

        // Verify database persistence of fare snapshot and distance
        Booking persisted = bookingRepository.findByBookingRef(response.bookingRef()).orElseThrow();
        assertEquals(new BigDecimal("1836.50"), persisted.getAmount());
        assertEquals(1447, persisted.getDistanceKm());
        assertNotNull(persisted.getFareBreakdownJson());
        assertTrue(persisted.getFareBreakdownJson().contains("\"bookingTotal\":1836.50"));
    }

    @Test
    void paymentAmount_matchesServerConfirmedBookingAmountExactly() {
        seatInventoryService.holdSeat(
                hwhNdlsJourney.getJourneyId(), seatA1.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "2A", "A1", testUser.getUsername());

        List<PassengerRequestDTO> passengers = List.of(
                new PassengerRequestDTO("Aditi Rao", 28, "FEMALE", seatA1.getSeatId(), "LOWER")
        );

        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                hwhNdlsJourney.getJourneyId(), null,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "2A", "A1", passengers
        );

        BookingResponseDTO created = bookingService.createBooking(request, testUser.getUsername());
        assertEquals(new BigDecimal("2606.68"), created.amount());

        // Process payment
        PaymentResponseDTO payment = bookingService.processPayment(
                created.bookingRef(),
                new PaymentRequestDTO("MOCK_UPI", "IDEM-PAY-TEST-01", false),
                testUser.getUsername()
        );

        assertEquals("SUCCESS", payment.paymentStatus());
        assertEquals("CONFIRMED", payment.bookingStatus());
        assertEquals(new BigDecimal("2606.68"), payment.amount());
    }

    @Test
    void travelClassAvailability_reflectsDynamicBaseFaresFromFareEngine() {
        List<TravelClassAvailabilityDTO> classList = seatInventoryService.getJourneyClassesAvailability(
                hwhNdlsJourney.getJourneyId(), hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId());

        assertNotNull(classList);
        assertEquals(3, classList.size());

        TravelClassAvailabilityDTO sl = classList.stream().filter(c -> "SL".equals(c.classCode())).findFirst().orElseThrow();
        TravelClassAvailabilityDTO threeA = classList.stream().filter(c -> "3A".equals(c.classCode())).findFirst().orElseThrow();
        TravelClassAvailabilityDTO twoA = classList.stream().filter(c -> "2A".equals(c.classCode())).findFirst().orElseThrow();

        assertEquals(new BigDecimal("701.15"), sl.baseFare());
        assertEquals(new BigDecimal("1836.50"), threeA.baseFare());
        assertEquals(new BigDecimal("2606.68"), twoA.baseFare());
    }

    @Test
    void unknownRouteDistance_throwsBadRequestException_andDoesNotFallBackToDefaultDistance() {
        // Create unknown stations not in configured corridors
        Station stA = new Station();
        stA.setStationCode("UNK1");
        stA.setStationName("Unknown Station 1");
        stA = stationRepository.save(stA);

        Station stB = new Station();
        stB.setStationCode("UNK2");
        stB.setStationName("Unknown Station 2");
        stB = stationRepository.save(stB);

        Train unkTrain = new Train();
        unkTrain.setTrainNumber("UNK-999");
        unkTrain.setTrainName("Unknown Train");
        unkTrain.setStatus("ACTIVE");
        unkTrain = trainRepository.save(unkTrain);

        Journey unkJourney = new Journey();
        unkJourney.setTrain(unkTrain);
        unkJourney.setFromStation(stA);
        unkJourney.setToStation(stB);
        unkJourney.setJourneyDate(LocalDate.of(2026, 12, 10));
        unkJourney.setDepartureTime(LocalTime.of(8, 0));
        unkJourney.setArrivalTime(LocalTime.of(12, 0));
        unkJourney = journeyRepository.save(unkJourney);

        JourneyStop unkStop1 = new JourneyStop();
        unkStop1.setJourney(unkJourney);
        unkStop1.setStation(stA);
        unkStop1.setStopSequence(1);
        unkStop1.setDepartureAt(LocalDateTime.of(2026, 12, 10, 8, 0));
        unkStop1 = journeyStopRepository.save(unkStop1);

        JourneyStop unkStop2 = new JourneyStop();
        unkStop2.setJourney(unkJourney);
        unkStop2.setStation(stB);
        unkStop2.setStopSequence(2);
        unkStop2.setArrivalAt(LocalDateTime.of(2026, 12, 10, 12, 0));
        unkStop2 = journeyStopRepository.save(unkStop2);

        // Neither stop metrics nor corridor distance exists: Must reject with BadRequestException
        final Long jId = unkJourney.getJourneyId();
        final Long s1Id = unkStop1.getJourneyStopId();
        final Long s2Id = unkStop2.getJourneyStopId();

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                fareCalculationService.calculateFareQuote(jId, "SL", 1, s1Id, s2Id));
        assertTrue(ex.getMessage().contains("No reliable route distance data available"));

        // Travel class availability must report null baseFare instead of 500.00
        Coach unkCoach = new Coach();
        unkCoach.setTrain(unkTrain);
        unkCoach.setCoachNumber("S1");
        unkCoach.setCoachType("SL");
        unkCoach.setSeatCapacity(2);
        unkCoach = coachRepository.save(unkCoach);

        Seat unkSeat = new Seat();
        unkSeat.setCoach(unkCoach);
        unkSeat.setSeatNumber(1);
        unkSeat.setSeatType("LOWER");
        unkSeat = seatRepository.save(unkSeat);

        createInventory(unkJourney, unkCoach, unkSeat);

        List<TravelClassAvailabilityDTO> unkClasses = seatInventoryService.getJourneyClassesAvailability(
                jId, s1Id, s2Id);
        assertNotNull(unkClasses);
        assertEquals(1, unkClasses.size());
        assertNull(unkClasses.get(0).baseFare(), "Unknown route distance must return null baseFare rather than 500.00 fallback");
    }

    @Test
    void fareCalculationFailure_preventsBookingCreation_andRollsBackAtomically() {
        // Create unknown stations and journey without distance
        Station stX = new Station();
        stX.setStationCode("NOKM1");
        stX.setStationName("No Km Station 1");
        stX = stationRepository.save(stX);

        Station stY = new Station();
        stY.setStationCode("NOKM2");
        stY.setStationName("No Km Station 2");
        stY = stationRepository.save(stY);

        Train train = new Train();
        train.setTrainNumber("NOKM-100");
        train.setTrainName("No Km Express");
        train.setStatus("ACTIVE");
        train = trainRepository.save(train);

        Journey journey = new Journey();
        journey.setTrain(train);
        journey.setFromStation(stX);
        journey.setToStation(stY);
        journey.setJourneyDate(LocalDate.of(2026, 12, 15));
        journey.setDepartureTime(LocalTime.of(9, 0));
        journey.setArrivalTime(LocalTime.of(15, 0));
        journey = journeyRepository.save(journey);

        JourneyStop stop1 = new JourneyStop();
        stop1.setJourney(journey);
        stop1.setStation(stX);
        stop1.setStopSequence(1);
        stop1.setDepartureAt(LocalDateTime.of(2026, 12, 15, 9, 0));
        stop1 = journeyStopRepository.save(stop1);

        JourneyStop stop2 = new JourneyStop();
        stop2.setJourney(journey);
        stop2.setStation(stY);
        stop2.setStopSequence(2);
        stop2.setArrivalAt(LocalDateTime.of(2026, 12, 15, 15, 0));
        stop2 = journeyStopRepository.save(stop2);

        Coach coach = new Coach();
        coach.setTrain(train);
        coach.setCoachNumber("S1");
        coach.setCoachType("SL");
        coach.setSeatCapacity(1);
        coach = coachRepository.save(coach);

        Seat seat = new Seat();
        seat.setCoach(coach);
        seat.setSeatNumber(1);
        seat.setSeatType("LOWER");
        seat = seatRepository.save(seat);

        createInventory(journey, coach, seat);

        // Hold seat
        seatInventoryService.holdSeat(
                journey.getJourneyId(), seat.getSeatId(), 10,
                stop1.getJourneyStopId(), stop2.getJourneyStopId(),
                "SL", "S1", testUser.getUsername());

        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                journey.getJourneyId(), seat.getSeatId(),
                stop1.getJourneyStopId(), stop2.getJourneyStopId(),
                "SL", "S1"
        );

        // Attempting to create booking must throw BadRequestException due to missing distance
        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                bookingService.createBooking(request, testUser.getUsername()));
        assertTrue(ex.getMessage().contains("No reliable route distance data available"));

        // Confirm zero bookings were created in the database
        assertTrue(bookingRepository.findAll().stream()
                .noneMatch(b -> b.getUser() != null && testUser.getUsername().equals(b.getUser().getUsername())));
    }

    @Test
    void singlePassengerPayment_chargesExactCalculatedAmount_forSleeper() {
        seatInventoryService.holdSeat(
                hwhNdlsJourney.getJourneyId(), seatS1.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "SL", "S1", testUser.getUsername());

        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                hwhNdlsJourney.getJourneyId(), seatS1.getSeatId(),
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "SL", "S1"
        );

        BookingResponseDTO created = bookingService.createBooking(request, testUser.getUsername());
        assertEquals(new BigDecimal("701.15"), created.amount(), "SL fare for 1447 km must be 701.15");

        PaymentResponseDTO payment = bookingService.processPayment(
                created.bookingRef(),
                new PaymentRequestDTO("MOCK_UPI", "IDEM-PAY-SL-01", false),
                testUser.getUsername()
        );

        assertEquals("SUCCESS", payment.paymentStatus());
        assertEquals("CONFIRMED", payment.bookingStatus());
        assertEquals(new BigDecimal("701.15"), payment.amount(), "Payment must charge exact calculated 701.15, not 500.00 mock fare");

        Booking persisted = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        assertEquals(new BigDecimal("701.15"), persisted.getAmount());
    }

    @Test
    void paymentRejection_whenBookingHasNoValidPersistedFare() {
        seatInventoryService.holdSeat(
                hwhNdlsJourney.getJourneyId(), seatB1.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", testUser.getUsername());

        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                hwhNdlsJourney.getJourneyId(), seatB1.getSeatId(),
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1"
        );

        BookingResponseDTO created = bookingService.createBooking(request, testUser.getUsername());

        // Simulate corrupted/legacy booking with null fare amount
        Booking booking = bookingRepository.findByBookingRef(created.bookingRef()).orElseThrow();
        booking.setAmount(null);
        bookingRepository.saveAndFlush(booking);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                bookingService.processPayment(
                        created.bookingRef(),
                        new PaymentRequestDTO("MOCK_UPI", "IDEM-NULL-FARE", false),
                        testUser.getUsername()
                ));
        assertTrue(ex.getMessage().contains("no valid persisted fare amount"));

        // Simulate zero amount
        booking.setAmount(BigDecimal.ZERO);
        bookingRepository.saveAndFlush(booking);

        IllegalStateException exZero = assertThrows(IllegalStateException.class, () ->
                bookingService.processPayment(
                        created.bookingRef(),
                        new PaymentRequestDTO("MOCK_UPI", "IDEM-ZERO-FARE", false),
                        testUser.getUsername()
                ));
        assertTrue(exZero.getMessage().contains("no valid persisted fare amount"));
    }

    @Test
    void multiPassengerPayment_chargesExactCalculatedBookingTotal_andSupportsIdempotency() {
        // Add 2nd seat in B1
        Seat seatB1_2 = new Seat();
        seatB1_2.setCoach(coachB1);
        seatB1_2.setSeatNumber(8);
        seatB1_2.setSeatType("MIDDLE");
        seatB1_2 = seatRepository.save(seatB1_2);
        createInventory(hwhNdlsJourney, coachB1, seatB1_2);

        seatInventoryService.holdSeat(
                hwhNdlsJourney.getJourneyId(), seatB1.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", testUser.getUsername());
        seatInventoryService.holdSeat(
                hwhNdlsJourney.getJourneyId(), seatB1_2.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", testUser.getUsername());

        List<PassengerRequestDTO> passengers = List.of(
                new PassengerRequestDTO("Aditi Rao", 28, "FEMALE", seatB1.getSeatId(), "LOWER"),
                new PassengerRequestDTO("Rahul Rao", 32, "MALE", seatB1_2.getSeatId(), "MIDDLE")
        );

        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                hwhNdlsJourney.getJourneyId(), null,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", passengers
        );

        BookingResponseDTO created = bookingService.createBooking(request, testUser.getUsername());
        // 2 passengers * 1836.50 = 3673.00
        assertEquals(new BigDecimal("3673.00"), created.amount());

        // Process payment
        PaymentResponseDTO payment1 = bookingService.processPayment(
                created.bookingRef(),
                new PaymentRequestDTO("MOCK_UPI", "IDEM-MULTI-01", false),
                testUser.getUsername()
        );
        assertEquals(new BigDecimal("3673.00"), payment1.amount());
        assertEquals("CONFIRMED", payment1.bookingStatus());

        // Idempotent retry
        PaymentResponseDTO payment2 = bookingService.processPayment(
                created.bookingRef(),
                new PaymentRequestDTO("MOCK_UPI", "IDEM-MULTI-01", false),
                testUser.getUsername()
        );
        assertEquals(payment1.transactionRef(), payment2.transactionRef());
        assertEquals(new BigDecimal("3673.00"), payment2.amount());
    }
}
