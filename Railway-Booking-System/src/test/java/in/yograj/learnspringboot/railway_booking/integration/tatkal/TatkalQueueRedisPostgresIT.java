package in.yograj.learnspringboot.railway_booking.integration.tatkal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.yograj.learnspringboot.railway_booking.dto.CreateTrainRouteRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.JourneyRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainStopRequestDTO;
import in.yograj.learnspringboot.railway_booking.entity.Journey;
import in.yograj.learnspringboot.railway_booking.entity.Station;
import in.yograj.learnspringboot.railway_booking.entity.Train;
import in.yograj.learnspringboot.railway_booking.entity.User;
import in.yograj.learnspringboot.railway_booking.repository.JourneyRepository;
import in.yograj.learnspringboot.railway_booking.repository.JourneySeatInventoryRepository;
import in.yograj.learnspringboot.railway_booking.repository.JourneyStopRepository;
import in.yograj.learnspringboot.railway_booking.repository.StationRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainRepository;
import in.yograj.learnspringboot.railway_booking.repository.UserRepository;
import in.yograj.learnspringboot.railway_booking.security.JwtService;
import in.yograj.learnspringboot.railway_booking.service.JourneyService;
import in.yograj.learnspringboot.railway_booking.service.TatkalQueueService;
import in.yograj.learnspringboot.railway_booking.service.TrainRouteService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class TatkalQueueRedisPostgresIT {
    private static final String TEST_DATABASE_URL =
            "jdbc:postgresql://127.0.0.1:15432/railora_tatkal_it";
    private static long expectedTatkalJourneyId;

    @DynamicPropertySource
    static void configureIsolatedServices(DynamicPropertyRegistry registry) {
        String databaseUrl = requiredEnvironment("RAILORA_TATKAL_TEST_PG_URL");
        if (!TEST_DATABASE_URL.equals(databaseUrl) || databaseUrl.contains("railway_booking")) {
            throw new IllegalStateException("Tatkal integration test requires its dedicated disposable PostgreSQL database");
        }

        String databasePassword = requiredEnvironment("RAILORA_TATKAL_TEST_PASSWORD");
        Flyway.configure()
                .dataSource(databaseUrl, "railora_test", databasePassword)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        expectedTatkalJourneyId = reserveNextJourneyId(databaseUrl, databasePassword) + 1;

        registry.add("spring.datasource.url", () -> databaseUrl);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "railora_test");
        registry.add("spring.datasource.password", () -> databasePassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> "16379");
        registry.add("tatkal.queue.enabled", () -> "true");
        registry.add("tatkal.queue.admission-secret",
                () -> requiredEnvironment("RAILORA_TATKAL_ADMISSION_SECRET"));
        registry.add("tatkal.queue.journey-ids", () -> Long.toString(expectedTatkalJourneyId));
        registry.add("tatkal.queue.capacity", () -> "1");
        registry.add("tatkal.queue.admission-ttl-seconds", () -> "300");
        registry.add("tatkal.queue.entry-ttl-seconds", () -> "86400");
    }

    @Autowired private WebApplicationContext webApplicationContext;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TrainRepository trainRepository;
    @Autowired private StationRepository stationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JourneyRepository journeyRepository;
    @Autowired private JourneyStopRepository journeyStopRepository;
    @Autowired private JourneySeatInventoryRepository inventoryRepository;
    @Autowired private TrainRouteService trainRouteService;
    @Autowired private JourneyService journeyService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private TatkalQueueService queueService;

    private MockMvc mockMvc;

    @BeforeEach
    void configureMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void queueAdmissionSeatHoldAndBookingUseIsolatedRedisAndPostgres() throws Exception {
        assertConnectedToDedicatedDatabase();
        Journey journey = createTatkalJourney();
        assertEquals(expectedTatkalJourneyId, journey.getJourneyId(),
                "The fixture journey must match the test-only Tatkal allowlist");

        String concurrentUsername = createUser("concurrent");
        List<String> concurrentEntries = concurrentJoins(journey.getJourneyId(), concurrentUsername);
        assertEquals(1, concurrentEntries.stream().distinct().count(),
                "Concurrent duplicate joins must resolve to one queue entry");
        assertTrue(queueService.getEntry(journey.getJourneyId(), concurrentEntries.get(0), concurrentUsername)
                .isPresent());
        mockMvc.perform(delete(queuePath(journey) + "/me")
                        .header("Authorization", bearer(concurrentUsername)))
                .andExpect(status().isNoContent());

        String leavingUsername = createUser("leaving");
        mockMvc.perform(post(queuePath(journey))
                        .header("Authorization", bearer(leavingUsername))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());
        mockMvc.perform(delete(queuePath(journey) + "/me")
                        .header("Authorization", bearer(leavingUsername)))
                .andExpect(status().isNoContent());

        String username = createUser("traveler");
        String authorization = bearer(username);
        MvcResult joined = mockMvc.perform(post(queuePath(journey))
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("journeyId", journey.getJourneyId(), "quota", "TATKAL"))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode joinedBody = objectMapper.readTree(joined.getResponse().getContentAsString());
        String entryId = joinedBody.path("queueEntryId").asText();
        String admissionPass = joinedBody.path("admissionPass").asText();
        assertEquals("ADMITTED", joinedBody.path("status").asText());
        assertFalse(entryId.isBlank());
        assertFalse(admissionPass.isBlank());

        MvcResult status = mockMvc.perform(get(queuePath(journey) + "/me")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode statusBody = objectMapper.readTree(status.getResponse().getContentAsString());
        assertEquals(entryId, statusBody.path("queueEntryId").asText());
        assertEquals("ADMITTED", statusBody.path("status").asText());
        assertFalse(statusBody.path("admissionPass").asText().isBlank());

        List<Station> stations = stationRepository.findAll();
        List<Long> stopIds = journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(
                        journey.getJourneyId()).stream()
                .map(stop -> stop.getJourneyStopId())
                .toList();
        List<Long> seatIds = inventoryRepository.findByJourney_JourneyId(journey.getJourneyId()).stream()
                .map(inventory -> inventory.getSeat().getSeatId())
                .toList();
        assertTrue(stations.size() >= 2);
        assertTrue(seatIds.size() >= 2);

        String holdBody = objectMapper.writeValueAsString(java.util.Map.of(
                "seatId", seatIds.get(0),
                "holdMinutes", 10,
                "boardingJourneyStopId", stopIds.get(0),
                "alightingJourneyStopId", stopIds.get(stopIds.size() - 1),
                "bookingMode", "TATKAL"));
        mockMvc.perform(post("/api/journeys/" + journey.getJourneyId() + "/seats/hold")
                        .header("Authorization", authorization)
                        .header("X-Tatkal-Admission", admissionPass)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody))
                .andExpect(status().isOk());

        String bookingBody = objectMapper.writeValueAsString(java.util.Map.of(
                "journeyId", journey.getJourneyId(),
                "seatId", seatIds.get(0),
                "boardingJourneyStopId", stopIds.get(0),
                "alightingJourneyStopId", stopIds.get(stopIds.size() - 1),
                "bookingMode", "TATKAL"));
        MvcResult booking = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", authorization)
                        .header("X-Tatkal-Admission", admissionPass)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode bookingBodyJson = objectMapper.readTree(booking.getResponse().getContentAsString());
        assertEquals("PENDING_PAYMENT", bookingBodyJson.path("bookingStatus").asText());
        assertFalse(bookingBodyJson.path("bookingRef").asText().isBlank());

        String directHoldBody = objectMapper.writeValueAsString(java.util.Map.of(
                "seatId", seatIds.get(1),
                "holdMinutes", 10,
                "boardingJourneyStopId", stopIds.get(0),
                "alightingJourneyStopId", stopIds.get(stopIds.size() - 1),
                "bookingMode", "TATKAL"));
        mockMvc.perform(post("/api/journeys/" + journey.getJourneyId() + "/seats/hold")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directHoldBody))
                .andExpect(status().isForbidden());
    }

    private List<String> concurrentJoins(long journeyId, String username) throws Exception {
        int callers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> requests = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                requests.add(executor.submit(() -> {
                    start.await();
                    return queueService.join(journeyId, username).queueEntryId();
                }));
            }
            start.countDown();
            List<String> entries = new ArrayList<>();
            for (Future<String> request : requests) {
                entries.add(request.get());
            }
            return entries;
        } finally {
            executor.shutdownNow();
        }
    }

    private Journey createTatkalJourney() {
        Train train = new Train();
        train.setTrainNumber("TATKAL-" + UUID.randomUUID().toString().substring(0, 8));
        train.setTrainName("Isolated Tatkal Integration Express");
        train.setStatus("ACTIVE");
        train = trainRepository.saveAndFlush(train);

        Station origin = station("ORG");
        Station destination = station("DST");
        var route = trainRouteService.createRoute(train.getTrainId(),
                new CreateTrainRouteRequestDTO("test-route-" + UUID.randomUUID(), List.of(
                        new TrainStopRequestDTO(origin.getStationId(), 1, null, LocalTime.of(8, 0), 0),
                        new TrainStopRequestDTO(destination.getStationId(), 2, LocalTime.of(9, 0), null, 0))));

        var response = journeyService.createJourney(new JourneyRequestDTO(
                train.getTrainId(), null, null, LocalDate.now().plusDays(10),
                null, null, route.trainRouteId()));
        return journeyRepository.findById(response.journeyId()).orElseThrow();
    }

    private Station station(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Station station = new Station();
        station.setStationCode(prefix + suffix);
        station.setStationName(prefix + " Integration Station");
        station.setCity("Test City");
        station.setState("Test State");
        return stationRepository.saveAndFlush(station);
    }

    private String createUser(String prefix) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername(username);
        user.setFullName("Tatkal Integration Passenger");
        user.setEmail(username + "@example.test");
        user.setPassword(passwordEncoder.encode("test-password"));
        user.setStatus("ACTIVE");
        user.setRole("ROLE_USER");
        userRepository.saveAndFlush(user);
        return username;
    }

    private String bearer(String username) {
        return "Bearer " + jwtService.generateAccessToken(username, "ROLE_USER");
    }

    private static String queuePath(Journey journey) {
        return "/api/tatkal/journeys/" + journey.getJourneyId() + "/queue";
    }

    private void assertConnectedToDedicatedDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
            assertEquals(TEST_DATABASE_URL, connection.getMetaData().getURL());
            assertEquals("railora_tatkal_it",
                    jdbcTemplate.queryForObject("select current_database()", String.class));
        }
    }

    private static long reserveNextJourneyId(String databaseUrl, String databasePassword) {
        try (Connection connection = DriverManager.getConnection(
                     databaseUrl, "railora_test", databasePassword);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT nextval(pg_get_serial_sequence('journeys', 'journey_id')::regclass)")) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Could not reserve the next isolated test journey ID");
            }
            return resultSet.getLong(1);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not configure the isolated Tatkal journey allowlist", exception);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for isolated Tatkal integration tests");
        }
        return value;
    }
}
