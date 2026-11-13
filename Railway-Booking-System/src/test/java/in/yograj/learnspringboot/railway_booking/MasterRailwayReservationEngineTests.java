package in.yograj.learnspringboot.railway_booking;

import in.yograj.learnspringboot.railway_booking.dto.*;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.*;
import in.yograj.learnspringboot.railway_booking.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:master-engine-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length",
        "railway.seat-hold-reaper.enabled=false"
})
class MasterRailwayReservationEngineTests {

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    @Autowired
    private TrainSearchService trainSearchService;

    @Autowired
    private JourneyService journeyService;

    @Autowired
    private SeatInventoryService seatInventoryService;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private FareCalculationService fareCalculationService;

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
    private SeatAllocationRepository seatAllocationRepository;

    @Autowired
    private JourneySeatInventoryRepository inventoryRepository;

    private User testUser1;
    private User testUser2;
    private Station stationHwh;
    private Station stationNdls;
    private Station stationSta;
    private Station stationKte;
    private Station stationJbp;
    private Train train12301;
    private Train trainKamayani;
    private Train trainMonWedFri;

    @BeforeEach
    void setUp() {
        testUser1 = userRepository.findByUsername("passenger1").orElseGet(() -> {
            User u = new User();
            u.setUsername("passenger1");
            u.setFullName("Passenger One");
            u.setEmail("passenger1@railora.test");
            u.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyzaaaaaaaaaaaaaaaaaaaaaaaa");
            u.setRole("USER");
            u.setStatus("ACTIVE");
            return userRepository.save(u);
        });

        testUser2 = userRepository.findByUsername("passenger2").orElseGet(() -> {
            User u = new User();
            u.setUsername("passenger2");
            u.setFullName("Passenger Two");
            u.setEmail("passenger2@railora.test");
            u.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyzaaaaaaaaaaaaaaaaaaaaaaaa");
            u.setRole("USER");
            u.setStatus("ACTIVE");
            return userRepository.save(u);
        });

        // Stations
        stationHwh = stationRepository.findByStationCode("HWH").orElseGet(() -> {
            Station s = new Station();
            s.setStationCode("HWH");
            s.setStationName("Howrah Junction");
            return stationRepository.save(s);
        });

        stationNdls = stationRepository.findByStationCode("NDLS").orElseGet(() -> {
            Station s = new Station();
            s.setStationCode("NDLS");
            s.setStationName("New Delhi");
            return stationRepository.save(s);
        });

        stationSta = stationRepository.findByStationCode("STA").orElseGet(() -> {
            Station s = new Station();
            s.setStationCode("STA");
            s.setStationName("Satna Junction");
            return stationRepository.save(s);
        });

        stationKte = stationRepository.findByStationCode("KTE").orElseGet(() -> {
            Station s = new Station();
            s.setStationCode("KTE");
            s.setStationName("Katni Junction");
            return stationRepository.save(s);
        });

        stationJbp = stationRepository.findByStationCode("JBP").orElseGet(() -> {
            Station s = new Station();
            s.setStationCode("JBP");
            s.setStationName("Jabalpur");
            return stationRepository.save(s);
        });

        // Train 1: 12301 Rajdhani Express (HWH -> NDLS)
        train12301 = trainRepository.findByTrainNumber("12301").orElseGet(() -> {
            Train t = new Train();
            t.setTrainNumber("12301");
            t.setTrainName("Howrah Rajdhani Express");
            t.setStatus("ACTIVE");
            t.setOperatingDays("DAILY");
            t.setClassProfile("RAJDHANI");
            return trainRepository.save(t);
        });

        List<TrainRoute> r1List = trainRouteRepository.findByTrain_TrainIdOrderByRouteName(train12301.getTrainId());
        TrainRoute route1 = r1List.isEmpty() ? null : r1List.get(0);
        if (route1 == null) {
            route1 = new TrainRoute();
            route1.setTrain(train12301);
            route1.setRouteName("HWH-NDLS Primary");
            route1 = trainRouteRepository.save(route1);

            TrainStop s1 = new TrainStop();
            s1.setTrainRoute(route1);
            s1.setStation(stationHwh);
            s1.setStopSequence(1);
            s1.setDepartureTime(LocalTime.of(16, 50));
            s1.setDayOffset(0);
            s1.setDistanceFromOriginKm(BigDecimal.ZERO);
            trainStopRepository.save(s1);

            TrainStop s2 = new TrainStop();
            s2.setTrainRoute(route1);
            s2.setStation(stationNdls);
            s2.setStopSequence(2);
            s2.setArrivalTime(LocalTime.of(10, 5));
            s2.setDayOffset(1);
            s2.setDistanceFromOriginKm(new BigDecimal("1447.00"));
            trainStopRepository.save(s2);
        }

        // Train 2: 11062 Kamayani Express (STA -> KTE -> JBP corridor)
        trainKamayani = trainRepository.findByTrainNumber("11062").orElseGet(() -> {
            Train t = new Train();
            t.setTrainNumber("11062");
            t.setTrainName("Kamayani Express");
            t.setStatus("ACTIVE");
            t.setOperatingDays("DAILY");
            t.setClassProfile("EXPRESS");
            return trainRepository.save(t);
        });

        List<TrainRoute> r2List = trainRouteRepository.findByTrain_TrainIdOrderByRouteName(trainKamayani.getTrainId());
        TrainRoute route2 = r2List.isEmpty() ? null : r2List.get(0);
        if (route2 == null) {
            route2 = new TrainRoute();
            route2.setTrain(trainKamayani);
            route2.setRouteName("STA-KTE-JBP Main");
            route2 = trainRouteRepository.save(route2);

            TrainStop ts1 = new TrainStop();
            ts1.setTrainRoute(route2);
            ts1.setStation(stationSta);
            ts1.setStopSequence(1);
            ts1.setDepartureTime(LocalTime.of(8, 0));
            ts1.setDayOffset(0);
            ts1.setDistanceFromOriginKm(BigDecimal.ZERO);
            trainStopRepository.save(ts1);

            TrainStop ts2 = new TrainStop();
            ts2.setTrainRoute(route2);
            ts2.setStation(stationKte);
            ts2.setStopSequence(2);
            ts2.setArrivalTime(LocalTime.of(9, 30));
            ts2.setDepartureTime(LocalTime.of(9, 35));
            ts2.setDayOffset(0);
            ts2.setDistanceFromOriginKm(new BigDecimal("98.00"));
            trainStopRepository.save(ts2);

            TrainStop ts3 = new TrainStop();
            ts3.setTrainRoute(route2);
            ts3.setStation(stationJbp);
            ts3.setStopSequence(3);
            ts3.setArrivalTime(LocalTime.of(11, 15));
            ts3.setDayOffset(0);
            ts3.setDistanceFromOriginKm(new BigDecimal("189.00"));
            trainStopRepository.save(ts3);
        }

        // Train 3: Special train operating only MON,WED,FRI
        trainMonWedFri = trainRepository.findByTrainNumber("19306").orElseGet(() -> {
            Train t = new Train();
            t.setTrainNumber("19306");
            t.setTrainName("Tri-Weekly Superfast");
            t.setStatus("ACTIVE");
            t.setOperatingDays("MON,WED,FRI");
            t.setClassProfile("EXPRESS");
            return trainRepository.save(t);
        });

        List<TrainRoute> r3List = trainRouteRepository.findByTrain_TrainIdOrderByRouteName(trainMonWedFri.getTrainId());
        TrainRoute route3 = r3List.isEmpty() ? null : r3List.get(0);
        if (route3 == null) {
            route3 = new TrainRoute();
            route3.setTrain(trainMonWedFri);
            route3.setRouteName("HWH-NDLS Special");
            route3 = trainRouteRepository.save(route3);

            TrainStop ts1 = new TrainStop();
            ts1.setTrainRoute(route3);
            ts1.setStation(stationHwh);
            ts1.setStopSequence(1);
            ts1.setDepartureTime(LocalTime.of(20, 0));
            ts1.setDayOffset(0);
            ts1.setDistanceFromOriginKm(BigDecimal.ZERO);
            trainStopRepository.save(ts1);

            TrainStop ts2 = new TrainStop();
            ts2.setTrainRoute(route3);
            ts2.setStation(stationNdls);
            ts2.setStopSequence(2);
            ts2.setArrivalTime(LocalTime.of(14, 0));
            ts2.setDayOffset(1);
            ts2.setDistanceFromOriginKm(new BigDecimal("1447.00"));
            trainStopRepository.save(ts2);
        }
    }

    @Test
    @DisplayName("Scenario A: Search 12301 by train number")
    void scenarioA_searchByTrainNumber() {
        List<TrainSearchResponseDTO> results = trainSearchService.searchTrainsUnified("12301", null, null, 0, 10);
        assertFalse(results.isEmpty(), "Search for 12301 should return results");
        assertEquals("12301", results.get(0).trainNumber());
        assertTrue(results.get(0).trainName().contains("Rajdhani"));
    }

    @Test
    @DisplayName("Scenario B: Search Rajdhani by train name")
    void scenarioB_searchByTrainName() {
        List<TrainSearchResponseDTO> results = trainSearchService.searchTrainsUnified("Rajdhani", null, null, 0, 10);
        assertFalse(results.isEmpty(), "Search for 'Rajdhani' should return results");
        assertTrue(results.stream().anyMatch(t -> t.trainName().toLowerCase().contains("rajdhani")));
    }

    @Test
    @DisplayName("Scenario C: Search STA → KTE (station-to-station query)")
    void scenarioC_searchStaToKte() {
        List<TrainSearchResponseDTO> results = trainSearchService.searchTrainsUnified("STA -> KTE", null, null, 0, 10);
        assertFalse(results.isEmpty(), "Search for 'STA -> KTE' should return connecting trains");
        assertEquals("11062", results.get(0).trainNumber());
    }

    @Test
    @DisplayName("Scenario D: Search HWH → NDLS (station-to-station direct and parsed)")
    void scenarioD_searchHwhToNdls() {
        List<TrainSearchResponseDTO> results = trainSearchService.searchTrains("HWH", "NDLS");
        assertFalse(results.isEmpty(), "Search between HWH and NDLS should return trains");
        assertTrue(results.stream().anyMatch(t -> "12301".equals(t.trainNumber())));
    }

    @Test
    @DisplayName("Scenario E: Book a supported train on a scheduled demo journey date")
    void scenarioE_bookSupportedTrainOnScheduledDate() {
        LocalDate travelDate = LocalDate.now().plusDays(7);
        Journey journey = journeyService.getOrCreateOperationalJourney(trainKamayani.getTrainId(), travelDate);
        assertNotNull(journey, "Operational journey must be created");

        List<JourneyStop> stops = journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(journey.getJourneyId());
        JourneyStop bStop = stops.get(0); // STA
        JourneyStop aStop = stops.get(1); // KTE

        // Get class availability
        List<TravelClassAvailabilityDTO> classAvail = seatInventoryService.getJourneyClassesAvailability(
                journey.getJourneyId(), bStop.getJourneyStopId(), aStop.getJourneyStopId());
        assertFalse(classAvail.isEmpty(), "Class availability should be generated");

        TravelClassAvailabilityDTO classSL = classAvail.stream()
                .filter(c -> "SL".equalsIgnoreCase(c.classCode()))
                .findFirst().orElseThrow();
        assertTrue(classSL.availableSeats() > 0, "SL class should have available seats");
        Long seatId = classSL.coaches().get(0).seats().get(0).seatId();

        // Hold seat (10 minutes)
        SeatAvailabilityResponseDTO heldSeat = seatInventoryService.holdSeat(
                journey.getJourneyId(), seatId, 10,
                bStop.getJourneyStopId(), aStop.getJourneyStopId(), "SL", "S1", testUser1.getUsername());
        assertEquals("HOLD", heldSeat.status());

        // Create booking
        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                journey.getJourneyId(), heldSeat.seatId(), bStop.getJourneyStopId(), aStop.getJourneyStopId(),
                "SL", "S1", List.of(new PassengerRequestDTO("Rahul Verma", 28, "MALE", heldSeat.seatId(), "LOWER"))
        );
        BookingResponseDTO bookingResponse = bookingService.createBooking(request, testUser1.getUsername(), null);
        assertNotNull(bookingResponse.bookingRef());
        assertNotNull(bookingResponse.pnr());
        assertEquals("PENDING_PAYMENT", bookingResponse.bookingStatus());

        // Process simulated payment
        PaymentResponseDTO payResponse = bookingService.processSimulatedPaymentSuccess(
                bookingResponse.bookingRef(), "sim-key-scen-e", testUser1.getUsername());
        assertEquals("SUCCESS", payResponse.paymentStatus());
        assertEquals("CONFIRMED", bookingService.getBookingByRef(bookingResponse.bookingRef(), testUser1.getUsername()).bookingStatus());
    }

    @Test
    @DisplayName("Scenario F: Try booking on a date when the train does not operate")
    void scenarioF_bookingRejectedOnNonOperatingDay() {
        // Train 19306 operates MON, WED, FRI.
        // Find a non-operating day
        LocalDate date = LocalDate.now().plusDays(1);
        while (date.getDayOfWeek() == DayOfWeek.MONDAY || date.getDayOfWeek() == DayOfWeek.WEDNESDAY || date.getDayOfWeek() == DayOfWeek.FRIDAY) {
            date = date.plusDays(1);
        }

        final LocalDate nonOperatingDate = date;
        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                journeyService.getOrCreateOperationalJourney(trainMonWedFri.getTrainId(), nonOperatingDate));
        assertTrue(ex.getMessage().contains("not scheduled to operate"),
                "Exception should state that the train is not scheduled to operate on this date");
    }

    @Test
    @DisplayName("Scenario G: Two users attempt to book the same seat for overlapping segments")
    void scenarioG_overlappingSegmentConflictPrevented() {
        LocalDate travelDate = LocalDate.now().plusDays(14);
        Journey journey = journeyService.getOrCreateOperationalJourney(trainKamayani.getTrainId(), travelDate);

        List<JourneyStop> stops = journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(journey.getJourneyId());
        JourneyStop stopSta = stops.get(0); // STA (seq 1)
        JourneyStop stopKte = stops.get(1); // KTE (seq 2)
        JourneyStop stopJbp = stops.get(2); // JBP (seq 3)

        List<TravelClassAvailabilityDTO> classes = seatInventoryService.getJourneyClassesAvailability(
                journey.getJourneyId(), stopSta.getJourneyStopId(), stopJbp.getJourneyStopId());
        TravelClassAvailabilityDTO classSL = classes.stream()
                .filter(c -> "SL".equalsIgnoreCase(c.classCode())).findFirst().orElseThrow();
        Long physicalSeatId = classSL.coaches().get(0).seats().get(0).seatId();

        // User 1 books STA -> KTE [1, 2)
        SeatAvailabilityResponseDTO user1Hold = seatInventoryService.holdSeat(
                journey.getJourneyId(), physicalSeatId, 10,
                stopSta.getJourneyStopId(), stopKte.getJourneyStopId(), "SL", "S1", testUser1.getUsername());
        CreateBookingRequestDTO req1 = new CreateBookingRequestDTO(
                journey.getJourneyId(), user1Hold.seatId(), stopSta.getJourneyStopId(), stopKte.getJourneyStopId(),
                "SL", "S1", List.of(new PassengerRequestDTO("Passenger A", 30, "MALE", user1Hold.seatId(), "LOWER"))
        );
        BookingResponseDTO book1 = bookingService.createBooking(req1, testUser1.getUsername(), null);
        assertEquals("PENDING_PAYMENT", book1.bookingStatus());
        bookingService.processSimulatedPaymentSuccess(book1.bookingRef(), "sim-key-g1", testUser1.getUsername());
        assertEquals("CONFIRMED", bookingService.getBookingByRef(book1.bookingRef(), testUser1.getUsername()).bookingStatus());

        // User 2 attempts to hold/book the same seat for overlapping segment STA -> JBP [1, 3)
        BadRequestException conflictEx = assertThrows(BadRequestException.class, () ->
                seatInventoryService.holdSeat(
                        journey.getJourneyId(), physicalSeatId, 10,
                        stopSta.getJourneyStopId(), stopJbp.getJourneyStopId(), "SL", "S1", testUser2.getUsername()));
        assertTrue(conflictEx.getMessage().toLowerCase().contains("already held")
                || conflictEx.getMessage().toLowerCase().contains("booked")
                || conflictEx.getMessage().toLowerCase().contains("unavailable")
                || conflictEx.getMessage().toLowerCase().contains("active allocation"));
    }

    @Test
    @DisplayName("Scenario H: Two users book the same physical berth for non-overlapping segments")
    void scenarioH_nonOverlappingSegmentsSameBerthSucceeds() {
        LocalDate travelDate = LocalDate.now().plusDays(21);
        Journey journey = journeyService.getOrCreateOperationalJourney(trainKamayani.getTrainId(), travelDate);

        List<JourneyStop> stops = journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(journey.getJourneyId());
        JourneyStop stopSta = stops.get(0); // STA (seq 1)
        JourneyStop stopKte = stops.get(1); // KTE (seq 2)
        JourneyStop stopJbp = stops.get(2); // JBP (seq 3)

        List<TravelClassAvailabilityDTO> classes = seatInventoryService.getJourneyClassesAvailability(
                journey.getJourneyId(), stopSta.getJourneyStopId(), stopJbp.getJourneyStopId());
        TravelClassAvailabilityDTO classSL = classes.stream()
                .filter(c -> "SL".equalsIgnoreCase(c.classCode())).findFirst().orElseThrow();
        Long physicalSeatId = classSL.coaches().get(0).seats().get(0).seatId();

        // User 1 reserves seat for STA -> KTE [1, 2)
        SeatAvailabilityResponseDTO hold1 = seatInventoryService.holdSeat(
                journey.getJourneyId(), physicalSeatId, 10,
                stopSta.getJourneyStopId(), stopKte.getJourneyStopId(), "SL", "S1", testUser1.getUsername());
        CreateBookingRequestDTO req1 = new CreateBookingRequestDTO(
                journey.getJourneyId(), hold1.seatId(), stopSta.getJourneyStopId(), stopKte.getJourneyStopId(),
                "SL", "S1", List.of(new PassengerRequestDTO("Passenger One", 25, "FEMALE", hold1.seatId(), "LOWER"))
        );
        BookingResponseDTO booking1 = bookingService.createBooking(req1, testUser1.getUsername(), null);
        assertEquals("PENDING_PAYMENT", booking1.bookingStatus());
        bookingService.processSimulatedPaymentSuccess(booking1.bookingRef(), "sim-key-h1", testUser1.getUsername());
        assertEquals("CONFIRMED", bookingService.getBookingByRef(booking1.bookingRef(), testUser1.getUsername()).bookingStatus());

        // User 2 reserves the SAME physical berth for non-overlapping segment KTE -> JBP [2, 3)
        SeatAvailabilityResponseDTO hold2 = seatInventoryService.holdSeat(
                journey.getJourneyId(), physicalSeatId, 10,
                stopKte.getJourneyStopId(), stopJbp.getJourneyStopId(), "SL", "S1", testUser2.getUsername());
        assertEquals("HOLD", hold2.status());
        CreateBookingRequestDTO req2 = new CreateBookingRequestDTO(
                journey.getJourneyId(), hold2.seatId(), stopKte.getJourneyStopId(), stopJbp.getJourneyStopId(),
                "SL", "S1", List.of(new PassengerRequestDTO("Passenger Two", 40, "MALE", hold2.seatId(), "LOWER"))
        );
        BookingResponseDTO booking2 = bookingService.createBooking(req2, testUser2.getUsername(), null);
        assertEquals("PENDING_PAYMENT", booking2.bookingStatus());
        bookingService.processSimulatedPaymentSuccess(booking2.bookingRef(), "sim-key-h2", testUser2.getUsername());
        assertEquals("CONFIRMED", bookingService.getBookingByRef(booking2.bookingRef(), testUser2.getUsername()).bookingStatus());

        // Assert both bookings point to the exact same physical seat
        assertEquals(booking1.seatId(), booking2.seatId());
        assertNotEquals(booking1.pnr(), booking2.pnr());
    }

    @Test
    @DisplayName("Scenario I: Cancel a confirmed booking and verify inventory is released")
    void scenarioI_cancellationReleasesInventory() {
        LocalDate travelDate = LocalDate.now().plusDays(28);
        Journey journey = journeyService.getOrCreateOperationalJourney(trainKamayani.getTrainId(), travelDate);

        List<JourneyStop> stops = journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(journey.getJourneyId());
        JourneyStop bStop = stops.get(0);
        JourneyStop aStop = stops.get(1);

        List<TravelClassAvailabilityDTO> initialClasses = seatInventoryService.getJourneyClassesAvailability(
                journey.getJourneyId(), bStop.getJourneyStopId(), aStop.getJourneyStopId());
        TravelClassAvailabilityDTO slInit = initialClasses.stream()
                .filter(c -> "SL".equalsIgnoreCase(c.classCode())).findFirst().orElseThrow();
        int initialAvailable = slInit.availableSeats();
        Long physicalSeatId = slInit.coaches().get(0).seats().get(0).seatId();

        // 1. Hold and confirm booking
        SeatAvailabilityResponseDTO hold = seatInventoryService.holdSeat(
                journey.getJourneyId(), physicalSeatId, 10,
                bStop.getJourneyStopId(), aStop.getJourneyStopId(), "SL", "S1", testUser1.getUsername());
        CreateBookingRequestDTO req = new CreateBookingRequestDTO(
                journey.getJourneyId(), hold.seatId(), bStop.getJourneyStopId(), aStop.getJourneyStopId(),
                "SL", "S1", List.of(new PassengerRequestDTO("Cancel Tester", 32, "MALE", hold.seatId(), "UPPER"))
        );
        BookingResponseDTO booked = bookingService.createBooking(req, testUser1.getUsername(), null);
        assertEquals("PENDING_PAYMENT", booked.bookingStatus());
        bookingService.processSimulatedPaymentSuccess(booked.bookingRef(), "sim-key-i1", testUser1.getUsername());
        assertEquals("CONFIRMED", bookingService.getBookingByRef(booked.bookingRef(), testUser1.getUsername()).bookingStatus());

        // Verify available seats decreased
        List<TravelClassAvailabilityDTO> midClasses = seatInventoryService.getJourneyClassesAvailability(
                journey.getJourneyId(), bStop.getJourneyStopId(), aStop.getJourneyStopId());
        int midAvailable = midClasses.stream().filter(c -> "SL".equalsIgnoreCase(c.classCode())).findFirst().orElseThrow().availableSeats();
        assertEquals(initialAvailable - 1, midAvailable);

        // 2. Cancel booking
        BookingDetailResponseDTO cancelled = bookingService.cancelBooking(booked.bookingRef(), testUser1.getUsername());
        assertEquals("CANCELLED", cancelled.bookingStatus());

        // 3. Verify seat allocation is CANCELLED and available seats restored
        List<TravelClassAvailabilityDTO> postClasses = seatInventoryService.getJourneyClassesAvailability(
                journey.getJourneyId(), bStop.getJourneyStopId(), aStop.getJourneyStopId());
        int restoredAvailable = postClasses.stream().filter(c -> "SL".equalsIgnoreCase(c.classCode())).findFirst().orElseThrow().availableSeats();
        assertEquals(initialAvailable, restoredAvailable, "Available seat count must be fully restored upon cancellation");
    }

    @Test
    @DisplayName("Scenario J: Search PNR and view confirmed ticket (public enquiry)")
    void scenarioJ_searchPnrPublicEnquiry() {
        LocalDate travelDate = LocalDate.now().plusDays(35);
        Journey journey = journeyService.getOrCreateOperationalJourney(train12301.getTrainId(), travelDate);

        List<JourneyStop> stops = journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(journey.getJourneyId());
        JourneyStop bStop = stops.get(0);
        JourneyStop aStop = stops.get(1);

        List<TravelClassAvailabilityDTO> classes = seatInventoryService.getJourneyClassesAvailability(
                journey.getJourneyId(), bStop.getJourneyStopId(), aStop.getJourneyStopId());
        TravelClassAvailabilityDTO class2A = classes.stream()
                .filter(c -> "2A".equalsIgnoreCase(c.classCode())).findFirst().orElseThrow();
        Long seatId = class2A.coaches().get(0).seats().get(0).seatId();

        SeatAvailabilityResponseDTO hold = seatInventoryService.holdSeat(
                journey.getJourneyId(), seatId, 10,
                bStop.getJourneyStopId(), aStop.getJourneyStopId(), "2A", "A1", testUser1.getUsername());
        BookingResponseDTO booked = bookingService.createBooking(
                new CreateBookingRequestDTO(journey.getJourneyId(), hold.seatId(), bStop.getJourneyStopId(), aStop.getJourneyStopId(),
                        "2A", "A1", List.of(new PassengerRequestDTO("PNR Inquirer", 45, "MALE", hold.seatId(), "LOWER"))),
                testUser1.getUsername(), null);
        assertEquals("PENDING_PAYMENT", booked.bookingStatus());
        bookingService.processSimulatedPaymentSuccess(booked.bookingRef(), "sim-key-j1", testUser1.getUsername());
        assertEquals("CONFIRMED", bookingService.getBookingByRef(booked.bookingRef(), testUser1.getUsername()).bookingStatus());

        // Public PNR enquiry with null username
        BookingDetailResponseDTO ticket = bookingService.getBookingByPnr(booked.pnr(), null);
        assertNotNull(ticket, "Public PNR enquiry must return ticket details");
        assertEquals(booked.pnr(), ticket.pnr());
        assertEquals("12301", ticket.trainNumber());
        assertEquals("HWH", ticket.fromStationCode());
        assertEquals("NDLS", ticket.toStationCode());
        assertEquals("CONFIRMED", ticket.bookingStatus());
        assertFalse(ticket.passengers().isEmpty());
    }

    @Test
    @DisplayName("Scenario K: Verify existing Train 12301 demo booking functionality still works")
    void scenarioK_verifyTrain12301DemoBookingWorks() {
        LocalDate demoDate = LocalDate.of(2026, 12, 1);
        Journey journey = journeyService.getOrCreateOperationalJourney(train12301.getTrainId(), demoDate);
        assertNotNull(journey);
        assertEquals("12301", journey.getTrain().getTrainNumber());

        List<JourneyResponseDTO> all = journeyService.getAllJourneys(train12301.getTrainId(), demoDate);
        assertFalse(all.isEmpty(), "Train 12301 must return operational journey on 2026-12-01");
        assertEquals("12301", all.get(0).trainNumber());
    }

    @Test
    @DisplayName("Scenario L: Concurrent journey creation returns same journey and creates no duplicate inventory")
    void scenarioL_concurrentOperationalJourneyCreation() throws Exception {
        LocalDate travelDate = LocalDate.of(2026, 12, 10);
        Long trainId = trainKamayani.getTrainId();

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<Journey>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return journeyService.getOrCreateOperationalJourney(trainId, travelDate);
            }));
        }

        List<Journey> results = new ArrayList<>();
        for (Future<Journey> f : futures) {
            results.add(f.get(15, TimeUnit.SECONDS));
        }
        executor.shutdown();

        // 1. All threads must succeed and return the exact same Journey ID
        Long expectedJourneyId = results.get(0).getJourneyId();
        assertNotNull(expectedJourneyId);
        for (Journey j : results) {
            assertEquals(expectedJourneyId, j.getJourneyId(), "All concurrent requests must return identical journeyId");
        }

        // 2. Exactly one journey record must exist in DB for this train and date
        List<Journey> dbJourneys = journeyRepository.findAll().stream()
                .filter(j -> j.getTrain().getTrainId().equals(trainId) && j.getJourneyDate().equals(travelDate))
                .toList();
        assertEquals(1, dbJourneys.size(), "Database must contain exactly 1 journey record");

        // 3. Verify no duplicate inventory exists
        List<JourneySeatInventory> inventories = inventoryRepository.findByJourney_JourneyId(expectedJourneyId);
        assertFalse(inventories.isEmpty(), "Inventories must be generated");

        long uniqueSeatCount = inventories.stream()
                .map(inv -> inv.getSeat().getSeatId())
                .distinct()
                .count();
        assertEquals(uniqueSeatCount, inventories.size(), "Inventory must contain no duplicate seats");
    }
}
