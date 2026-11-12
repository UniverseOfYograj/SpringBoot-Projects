package in.yograj.learnspringboot.railway_booking.security;

import in.yograj.learnspringboot.railway_booking.dto.BookingMode;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:stage4-security-tests;DB_CLOSE_DELAY=-1",
        "DB_USERNAME=sa",
        "DB_PASSWORD=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:stage4-security-tests;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length"
})
@Transactional
class Stage4RepositorySecurityIntegrationTests {

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private PassengerRepository passengerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StationRepository stationRepository;

    @Autowired
    private TrainRepository trainRepository;

    @Autowired
    private TrainRouteRepository trainRouteRepository;

    @Autowired
    private CoachRepository coachRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private JourneyRepository journeyRepository;

    @Autowired
    private JourneySeatInventoryRepository inventoryRepository;

    @Autowired
    private SeatAllocationRepository allocationRepository;

    @Autowired
    private JourneyStopRepository journeyStopRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // =========================================================================
    // 1. REPOSITORY INTEGRATION TESTS
    // =========================================================================

    @Test
    @DisplayName("PassengerRepository & BookingRepository persist and query multi-passenger relations with PNR")
    void passengerAndBookingRepositoryQueryVerification() {
        // Create baseline entities
        User user = new User();
        user.setUsername("testuser_s4_" + System.nanoTime());
        user.setPassword("$2a$10$dummyHashValueForTestingOnly12345678901234567890");
        user.setRole("ROLE_USER");
        user.setFullName("Test User");
        user.setEmail("test_" + System.nanoTime() + "@example.com");
        user.setPhoneNumber("9999999999");
        user = userRepository.save(user);

        Station st1 = new Station();
        st1.setStationCode("HWH_" + (System.nanoTime() % 10000));
        st1.setStationName("Howrah Junction");
        st1.setCity("Kolkata");
        st1.setState("West Bengal");
        st1 = stationRepository.save(st1);

        Station st2 = new Station();
        st2.setStationCode("NDLS_" + (System.nanoTime() % 10000));
        st2.setStationName("New Delhi");
        st2.setCity("New Delhi");
        st2.setState("Delhi");
        st2 = stationRepository.save(st2);

        Train train = new Train();
        train.setTrainNumber("12301_" + (System.nanoTime() % 10000));
        train.setTrainName("Rajdhani Express");
        train = trainRepository.save(train);

        TrainRoute route = new TrainRoute();
        route.setTrain(train);
        route.setRouteName("HWH-NDLS");
        route = trainRouteRepository.save(route);

        Coach coach = new Coach();
        coach.setTrain(train);
        coach.setCoachNumber("B1");
        coach.setCoachType("3A");
        coach.setSeatCapacity(6);
        coach = coachRepository.save(coach);

        Seat seat1 = new Seat();
        seat1.setCoach(coach);
        seat1.setSeatNumber(1);
        seat1.setSeatType("LOWER");
        seat1 = seatRepository.save(seat1);

        Seat seat2 = new Seat();
        seat2.setCoach(coach);
        seat2.setSeatNumber(2);
        seat2.setSeatType("UPPER");
        seat2 = seatRepository.save(seat2);

        Journey journey = new Journey();
        journey.setTrain(train);
        journey.setTrainRoute(route);
        journey.setFromStation(st1);
        journey.setToStation(st2);
        journey.setJourneyDate(LocalDate.now().plusDays(5));
        journey.setDepartureTime(LocalTime.of(16, 50));
        journey.setArrivalTime(LocalTime.of(10, 0));
        journey = journeyRepository.save(journey);

        JourneyStop js1 = new JourneyStop();
        js1.setJourney(journey);
        js1.setStation(st1);
        js1.setStopSequence(1);
        js1.setDistanceFromOriginKm(BigDecimal.ZERO);
        js1 = journeyStopRepository.save(js1);

        JourneyStop js2 = new JourneyStop();
        js2.setJourney(journey);
        js2.setStation(st2);
        js2.setStopSequence(2);
        js2.setDistanceFromOriginKm(new BigDecimal("1447.00"));
        js2 = journeyStopRepository.save(js2);

        JourneySeatInventory inv1 = new JourneySeatInventory();
        inv1.setJourney(journey);
        inv1.setCoach(coach);
        inv1.setSeat(seat1);
        inv1.setStatus("AVAILABLE");
        inv1 = inventoryRepository.save(inv1);

        JourneySeatInventory inv2 = new JourneySeatInventory();
        inv2.setJourney(journey);
        inv2.setCoach(coach);
        inv2.setSeat(seat2);
        inv2.setStatus("AVAILABLE");
        inv2 = inventoryRepository.save(inv2);

        SeatAllocation alloc1 = new SeatAllocation();
        alloc1.setInventory(inv1);
        alloc1.setBoardingStop(js1);
        alloc1.setAlightingStop(js2);
        alloc1.setFromStopSeq(1);
        alloc1.setToStopSeq(2);
        alloc1.setHoldOwner(user);
        alloc1.setStatus("HOLD");
        alloc1.setBookingMode(BookingMode.GENERAL);
        alloc1 = allocationRepository.save(alloc1);

        SeatAllocation alloc2 = new SeatAllocation();
        alloc2.setInventory(inv2);
        alloc2.setBoardingStop(js1);
        alloc2.setAlightingStop(js2);
        alloc2.setFromStopSeq(1);
        alloc2.setToStopSeq(2);
        alloc2.setHoldOwner(user);
        alloc2.setStatus("HOLD");
        alloc2.setBookingMode(BookingMode.GENERAL);
        alloc2 = allocationRepository.save(alloc2);

        // Create booking with canonical PNR and multi-passenger
        String pnr = "987654" + (System.nanoTime() % 10000);
        Booking booking = new Booking();
        booking.setUser(user);
        booking.setJourney(journey);
        booking.setCoach(coach);
        booking.setSeat(seat1);
        booking.setInventory(inv1);
        booking.setSeatAllocation(alloc1);
        booking.setBookingRef("BR-" + System.nanoTime());
        booking.setPnr(pnr);
        booking.setBookingStatus("CONFIRMED");
        booking.setPaymentStatus("SUCCESS");
        booking.setAmount(new BigDecimal("2880.00"));
        booking.setDistanceKm(1447);
        booking.setFareBreakdownJson("{\"distanceKm\":1447,\"total\":2880.00}");

        Passenger p1 = new Passenger();
        p1.setBooking(booking);
        p1.setSeatAllocation(alloc1);
        p1.setSeat(seat1);
        p1.setCoach(coach);
        p1.setFullName("Aarav Sharma");
        p1.setAge(30);
        p1.setGender("MALE");
        p1.setBerthPreference("LOWER");

        Passenger p2 = new Passenger();
        p2.setBooking(booking);
        p2.setSeatAllocation(alloc2);
        p2.setSeat(seat2);
        p2.setCoach(coach);
        p2.setFullName("Diya Sharma");
        p2.setAge(28);
        p2.setGender("FEMALE");
        p2.setBerthPreference("UPPER");

        booking.addPassenger(p1);
        booking.addPassenger(p2);

        booking = bookingRepository.save(booking);

        // 1. Verify PNR existence and lookup
        assertThat(bookingRepository.existsByPnr(pnr)).isTrue();
        Optional<Booking> pnrFound = bookingRepository.findByPnr(pnr);
        assertThat(pnrFound).isPresent();
        assertThat(pnrFound.get().getBookingRef()).isEqualTo(booking.getBookingRef());
        assertThat(pnrFound.get().getPassengers()).hasSize(2);

        // 2. Verify PassengerRepository lookups
        List<Passenger> passengers = passengerRepository.findByBooking_BookingId(booking.getBookingId());
        assertThat(passengers).hasSize(2);
        assertThat(passengerRepository.existsBySeatAllocation_SeatAllocationId(alloc1.getSeatAllocationId())).isTrue();
        assertThat(passengerRepository.existsBySeatAllocation_SeatAllocationId(alloc2.getSeatAllocationId())).isTrue();

        // 3. Verify BookingRepository multi-seat allocation query
        assertThat(bookingRepository.existsByAllocationIdAndBookingStatus(alloc1.getSeatAllocationId(), "CONFIRMED")).isTrue();
        assertThat(bookingRepository.existsByAllocationIdAndBookingStatus(alloc2.getSeatAllocationId(), "CONFIRMED")).isTrue();
        assertThat(bookingRepository.existsByAllocationIdAndBookingStatus(999999L, "CONFIRMED")).isFalse();
    }

    // =========================================================================
    // 2. SECURITY CONFIGURATION INTEGRATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Actuator health probe is public without credentials")
    void actuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.junit.jupiter.api.Assertions.assertTrue(status == 200 || status == 503,
                            "Expected public health endpoint to return 200 or 503 (depending on Redis status), but got: " + status);
                });
    }

    @Test
    @DisplayName("Actuator Prometheus scrape endpoint requires ADMIN authorization")
    void actuatorPrometheusRequiresAdmin() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Razorpay webhook endpoint is publicly accessible without JWT")
    void razorpayWebhookIsPublic() throws Exception {
        // POST to razorpay webhook without valid signature/payload should return 400 Bad Request from controller, not 401 from Security
        mockMvc.perform(post("/api/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Tatkal queue endpoints require authentication")
    void tatkalEndpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/api/tatkal/1/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Booking creation and retrieval require authentication")
    void bookingEndpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/api/bookings/my-bookings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Batch seat hold endpoint requires authentication")
    void batchSeatHoldRequiresAuth() throws Exception {
        mockMvc.perform(post("/api/journeys/1/seats/hold-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[1,2]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CORS preflight request allows configured origin and headers")
    void corsPreflightBehavior() throws Exception {
        mockMvc.perform(options("/api/trains/search")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization,Content-Type,X-Tatkal-Admission"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().exists("Access-Control-Allow-Methods"));
    }
}
