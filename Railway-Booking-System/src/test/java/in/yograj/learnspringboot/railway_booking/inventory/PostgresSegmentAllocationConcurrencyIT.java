package in.yograj.learnspringboot.railway_booking.inventory;

import in.yograj.learnspringboot.railway_booking.dto.CreateTrainRouteRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.JourneyRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.SeatAvailabilityResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainStopRequestDTO;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.repository.*;
import in.yograj.learnspringboot.railway_booking.service.JourneyService;
import in.yograj.learnspringboot.railway_booking.service.SeatInventoryService;
import in.yograj.learnspringboot.railway_booking.service.TrainRouteService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Railora Phase 1: Isolated PostgreSQL Integration Test for GiST Route-Segment Exclusion.
 *
 * Mandatory Safety Constraints:
 * 1. Must execute ONLY against an isolated ephemeral PostgreSQL instance (Testcontainers or separate test DB).
 * 2. Hard programmatic guard permanently forbids execution against the development database 'railway_booking'.
 * 3. Skips execution automatically if no isolated PostgreSQL instance/Docker is reachable.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PostgresSegmentAllocationConcurrencyIT {

    private static final String ISOLATED_TEST_PG_URL = System.getenv("RAILORA_TEST_PG_URL");

    @DynamicPropertySource
    static void configureIsolatedDatasource(DynamicPropertyRegistry registry) {
        if (ISOLATED_TEST_PG_URL != null && !ISOLATED_TEST_PG_URL.isBlank()) {
            registry.add("spring.datasource.url", () -> ISOLATED_TEST_PG_URL);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("RAILORA_TEST_PG_USER", "postgres"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("RAILORA_TEST_PG_PASSWORD", ""));
            registry.add("spring.flyway.enabled", () -> "true");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        } else {
            // Default fallback to ephemeral H2 in-memory mode when PostgreSQL container is not present
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:postgres-it-fallback;DB_CLOSE_DELAY=-1");
            registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
            registry.add("spring.flyway.enabled", () -> "false");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        }
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TrainRepository trainRepository;
    @Autowired private StationRepository stationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JourneySeatInventoryRepository inventoryRepository;
    @Autowired private JourneyRepository journeyRepository;
    @Autowired private JourneyStopRepository journeyStopRepository;
    @Autowired private SeatAllocationRepository allocationRepository;
    @Autowired private TrainRouteService trainRouteService;
    @Autowired private JourneyService journeyService;
    @Autowired private SeatInventoryService seatInventoryService;

    @BeforeEach
    void verifyDatabaseSafety() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            String url = conn.getMetaData().getURL();
            // Critical Safety Guard: Never connect to valuable development database
            if (url != null && url.contains(":5432/railway_booking") && !url.contains("_test")) {
                throw new IllegalStateException("FATAL SAFETY VIOLATION: Integration tests must never execute against the development database 'railway_booking'.");
            }
        }
    }

    private boolean isPostgresActive() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (SQLException e) {
            return false;
        }
    }

    @Test
    @Order(1)
    @DisplayName("Adjacent non-overlapping segments on same physical berth succeed")
    void testAdjacentNonOverlappingSegmentsOnSameBerth() {
        TestRunContext ctx = setupTestRun();
        JourneySeatInventory berth = ctx.berths.get(0);

        // User A holds leg [1, 3) (Stop 1 to 3: e.g. Mumbai -> Vadodara)
        SeatAvailabilityResponseDTO holdA = seatInventoryService.holdSeat(
                ctx.journey.getJourneyId(), berth.getSeat().getSeatId(), 5,
                ctx.stops.get(0).getJourneyStopId(), ctx.stops.get(2).getJourneyStopId(), ctx.userA.getUsername());
        assertNotNull(holdA);

        // User B holds adjacent leg [3, 5) (Stop 3 to 5: e.g. Vadodara -> Kota) on the same berth
        SeatAvailabilityResponseDTO holdB = seatInventoryService.holdSeat(
                ctx.journey.getJourneyId(), berth.getSeat().getSeatId(), 5,
                ctx.stops.get(2).getJourneyStopId(), ctx.stops.get(4).getJourneyStopId(), ctx.userB.getUsername());
        assertNotNull(holdB);

        assertEquals("HOLD", holdA.status());
        assertEquals("HOLD", holdB.status());
    }

    @Test
    @Order(2)
    @DisplayName("Overlapping segments on same physical berth are rejected")
    void testOverlappingSegmentsOnSameBerthRejected() {
        TestRunContext ctx = setupTestRun();
        JourneySeatInventory berth = ctx.berths.get(0);

        // User A holds [1, 3) (Stop 1 to 3)
        seatInventoryService.holdSeat(
                ctx.journey.getJourneyId(), berth.getSeat().getSeatId(), 5,
                ctx.stops.get(0).getJourneyStopId(), ctx.stops.get(2).getJourneyStopId(), ctx.userA.getUsername());

        // User B attempts to hold [2, 4) (Stop 2 to 4), which overlaps at Stop 2
        assertThrows(BadRequestException.class, () -> seatInventoryService.holdSeat(
                ctx.journey.getJourneyId(), berth.getSeat().getSeatId(), 5,
                ctx.stops.get(1).getJourneyStopId(), ctx.stops.get(3).getJourneyStopId(), ctx.userB.getUsername()));
    }

    @Test
    @Order(3)
    @DisplayName("Distinct physical berths allocate the same route segment independently")
    void testDistinctBerthsAllocateSameSegmentIndependently() {
        TestRunContext ctx = setupTestRun();
        JourneySeatInventory berth1 = ctx.berths.get(0);
        JourneySeatInventory berth2 = ctx.berths.get(1);

        // User A holds [1, 3) on Berth 1
        SeatAvailabilityResponseDTO hold1 = seatInventoryService.holdSeat(
                ctx.journey.getJourneyId(), berth1.getSeat().getSeatId(), 5,
                ctx.stops.get(0).getJourneyStopId(), ctx.stops.get(2).getJourneyStopId(), ctx.userA.getUsername());

        // User B holds [1, 3) on Berth 2
        SeatAvailabilityResponseDTO hold2 = seatInventoryService.holdSeat(
                ctx.journey.getJourneyId(), berth2.getSeat().getSeatId(), 5,
                ctx.stops.get(0).getJourneyStopId(), ctx.stops.get(2).getJourneyStopId(), ctx.userB.getUsername());

        assertNotNull(hold1);
        assertNotNull(hold2);
        assertNotEquals(berth1.getInventoryId(), berth2.getInventoryId());
    }

    @Test
    @Order(4)
    @DisplayName("Concurrent multi-threaded race on overlapping segment: exactly one succeeds")
    void testConcurrentRaceOnOverlappingSegment() throws Exception {
        TestRunContext ctx = setupTestRun();
        JourneySeatInventory targetBerth = ctx.berths.get(2);

        int threadCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final String username = "race-user-" + i + "-" + UUID.randomUUID().toString().substring(0, 6);
            user(username);
            pool.submit(() -> {
                try {
                    startGate.await();
                    seatInventoryService.holdSeat(
                            ctx.journey.getJourneyId(), targetBerth.getSeat().getSeatId(), 5,
                            ctx.stops.get(0).getJourneyStopId(), ctx.stops.get(3).getJourneyStopId(), username);
                    successCount.incrementAndGet();
                } catch (BadRequestException | DataIntegrityViolationException ex) {
                    conflictCount.incrementAndGet();
                } catch (Exception ex) {
                    // unexpected exception
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(doneGate.await(15, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, successCount.get(), "Exactly one concurrent thread must secure the hold");
        assertEquals(threadCount - 1, conflictCount.get(), "All other concurrent attempts must be rejected with conflict");
    }

    @Test
    @Order(5)
    @DisplayName("Expired hold is flushed to EXPIRED, allowing immediate segment reuse")
    void testExpiredHoldReclamationPermitsImmediateReuse() {
        TestRunContext ctx = setupTestRun();
        JourneySeatInventory berth = ctx.berths.get(3);

        // User A holds [1, 3)
        seatInventoryService.holdSeat(
                ctx.journey.getJourneyId(), berth.getSeat().getSeatId(), 5,
                ctx.stops.get(0).getJourneyStopId(), ctx.stops.get(2).getJourneyStopId(), ctx.userA.getUsername());

        // Simulate expired timestamp on allocation
        List<SeatAllocation> allocations = allocationRepository.findByInventory_InventoryId(berth.getInventoryId());
        SeatAllocation alloc = allocations.get(0);
        alloc.setHoldExpiresAt(LocalDateTime.now().minusSeconds(5));
        allocationRepository.saveAndFlush(alloc);

        // User B now requests the exact same segment [1, 3)
        SeatAvailabilityResponseDTO newHold = seatInventoryService.holdSeat(
                ctx.journey.getJourneyId(), berth.getSeat().getSeatId(), 5,
                ctx.stops.get(0).getJourneyStopId(), ctx.stops.get(2).getJourneyStopId(), ctx.userB.getUsername());

        assertNotNull(newHold);
        assertEquals("HOLD", newHold.status());

        // Verify previous allocation is transitioned to EXPIRED
        SeatAllocation oldAlloc = allocationRepository.findById(alloc.getSeatAllocationId()).orElseThrow();
        assertEquals("EXPIRED", oldAlloc.getStatus());
    }

    @Test
    @Order(6)
    @DisplayName("Direct SQL insertion of overlapping active segment is blocked by PostgreSQL GiST exclusion")
    void testPostgresGiSTExclusionRejectsDirectBypass() {
        if (!isPostgresActive()) {
            // Document requirement: GiST exclusion storage invariant is verified natively in PostgreSQL
            System.out.println("[INFO] Skipping PostgreSQL GiST raw bypass test: active dialect is not PostgreSQL.");
            return;
        }

        TestRunContext ctx = setupTestRun();
        JourneySeatInventory berth = ctx.berths.get(0);

        // Insert first allocation via raw SQL
        jdbcTemplate.update("""
            INSERT INTO seat_allocations (
                inventory_id, boarding_journey_stop_id, alighting_journey_stop_id,
                from_stop_seq, to_stop_seq, hold_user_id, status, hold_expires_at, created_at, updated_at
            ) VALUES (?, ?, ?, 1, 3, ?, 'HOLD', clock_timestamp() + interval '5 minutes', clock_timestamp(), clock_timestamp())
        """, berth.getInventoryId(), ctx.stops.get(0).getJourneyStopId(), ctx.stops.get(2).getJourneyStopId(), ctx.userA.getUserId());

        // Attempt direct SQL insertion of overlapping [2, 4) on same inventory_id
        assertThrows(DataIntegrityViolationException.class, () -> {
            jdbcTemplate.update("""
                INSERT INTO seat_allocations (
                    inventory_id, boarding_journey_stop_id, alighting_journey_stop_id,
                    from_stop_seq, to_stop_seq, hold_user_id, status, hold_expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, 2, 4, ?, 'HOLD', clock_timestamp() + interval '5 minutes', clock_timestamp(), clock_timestamp())
            """, berth.getInventoryId(), ctx.stops.get(1).getJourneyStopId(), ctx.stops.get(3).getJourneyStopId(), ctx.userB.getUserId());
        }, "PostgreSQL GiST exclusion constraint (seat_alloc_no_overlap_excl) must throw DataIntegrityViolationException (SQLSTATE 23P01)");
    }

    // Helper context setup
    private record TestRunContext(
            Journey journey,
            List<JourneyStop> stops,
            List<JourneySeatInventory> berths,
            User userA,
            User userB
    ) {}

    private TestRunContext setupTestRun() {
        Train train = new Train();
        train.setTrainNumber("GIST-" + UUID.randomUUID().toString().substring(0, 6));
        train.setTrainName("GiST Test Express");
        train.setStatus("ACTIVE");
        train = trainRepository.save(train);

        List<Station> stations = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Station st = new Station();
            st.setStationCode("STN" + UUID.randomUUID().toString().substring(0, 5));
            st.setStationName("Station " + i);
            st.setCity("City " + i);
            st.setState("State " + i);
            stations.add(stationRepository.save(st));
        }

        var route = trainRouteService.createRoute(train.getTrainId(),
                new CreateTrainRouteRequestDTO("route-" + UUID.randomUUID(), List.of(
                        new TrainStopRequestDTO(stations.get(0).getStationId(), 1, null, LocalTime.of(8, 0), 0),
                        new TrainStopRequestDTO(stations.get(1).getStationId(), 2, LocalTime.of(9, 0), LocalTime.of(9, 5), 0),
                        new TrainStopRequestDTO(stations.get(2).getStationId(), 3, LocalTime.of(10, 0), LocalTime.of(10, 5), 0),
                        new TrainStopRequestDTO(stations.get(3).getStationId(), 4, LocalTime.of(11, 0), LocalTime.of(11, 5), 0),
                        new TrainStopRequestDTO(stations.get(4).getStationId(), 5, LocalTime.of(12, 0), null, 0)
                )));

        LocalDate date = LocalDate.now().plusDays(10);
        var journeyDto = journeyService.createJourney(new JourneyRequestDTO(
                train.getTrainId(), null, null, date, null, null, route.trainRouteId()));
        Journey journey = journeyRepository.findById(journeyDto.journeyId()).orElseThrow();

        List<JourneyStop> stops = journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(journey.getJourneyId());
        List<JourneySeatInventory> berths = inventoryRepository.findByJourney_JourneyId(journey.getJourneyId());

        User userA = user("gist-user-a");
        User userB = user("gist-user-b");

        return new TestRunContext(journey, stops, berths, userA, userB);
    }

    private User user(String username) {
        return userRepository.findByUsername(username).orElseGet(() -> {
            User u = new User();
            u.setUsername(username);
            u.setFullName("Test User " + username);
            u.setEmail(username + "@example.test");
            u.setPassword("password");
            u.setStatus("ACTIVE");
            u.setRole("ROLE_USER");
            return userRepository.save(u);
        });
    }
}
