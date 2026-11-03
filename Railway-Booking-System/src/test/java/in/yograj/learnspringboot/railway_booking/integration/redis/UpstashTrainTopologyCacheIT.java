package in.yograj.learnspringboot.railway_booking.integration.redis;

import in.yograj.learnspringboot.railway_booking.config.RedisCacheConfig;
import in.yograj.learnspringboot.railway_booking.dto.CoachTopologyDTO;
import in.yograj.learnspringboot.railway_booking.dto.SeatTopologyDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainTopologyDTO;
import in.yograj.learnspringboot.railway_booking.entity.Coach;
import in.yograj.learnspringboot.railway_booking.entity.Seat;
import in.yograj.learnspringboot.railway_booking.entity.Train;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.*;
import in.yograj.learnspringboot.railway_booking.service.TrainTopologyChangedEvent;
import in.yograj.learnspringboot.railway_booking.service.TrainTopologyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Phase 2: Real Upstash Redis Train Topology Cache Integration Verification.
 *
 * Verifies that Railora's static Train and Coach layout/topology caching implementations
 * operate correctly with the live Upstash Redis instance (over TLS).
 *
 * Verification Scope:
 * 1. Train layout caching populates actual Redis cache ("trainTopology::train:<trainId>").
 * 2. Repeated train layout request produces a cache hit without querying underlying repositories.
 * 3. Coach layout caching populates actual Redis cache ("trainTopology::coach:<coachId>").
 * 4. Repeated coach layout request produces a cache hit without querying underlying repositories.
 * 5. Distinct cache keys and namespaces between trains and coaches (even with identical numeric IDs).
 * 6. Configured TTL values match expected duration: 24 hours (~86400 seconds).
 * 7. Polymorphic JSON serialization/deserialization fidelity into TrainTopologyDTO and CoachTopologyDTO records.
 * 8. Targeted cache eviction: direct eviction and event-driven invalidation via TrainTopologyChangedEvent.
 * 9. Fail-open resilience: Redis read/write errors fall back to database queries without disruption.
 * 10. Dynamic seat inventory/hold statuses remain strictly excluded from Redis topology caching.
 *
 * Activation:
 * This test is strictly opt-in and skipped during regular build/test runs.
 * To activate for live verification:
 *   set RUN_UPSTASH_SMOKE_TEST=true
 *   or pass: -Dupstash.smoke.enabled=true
 *
 * Safety Boundaries:
 * - Uses in-memory H2 database; zero interaction with PostgreSQL "railway_booking" database.
 * - Never logs or displays REDIS_PASSWORD.
 * - Tracks and deletes only the exact test keys created during execution; NO FLUSHDB / FLUSHALL.
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:upstash-topology-cache-it;DB_CLOSE_DELAY=-1",
        "DB_USERNAME=sa",
        "DB_PASSWORD=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:upstash-topology-cache-it;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "railway.outbox.kafka.enabled=false",
        "railway.seat-hold-reaper.enabled=false",
        "railway.data-seed.admin.enabled=false",
        "railway.data-seed.journey.enabled=false",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length"
})
@EnabledIf("isOptedIn")
class UpstashTrainTopologyCacheIT {

    private static final Logger log = LoggerFactory.getLogger(UpstashTrainTopologyCacheIT.class);

    // Track all Redis keys created by this test run for isolated, guaranteed cleanup
    private final Set<String> trackedKeys = ConcurrentHashMap.newKeySet();

    @Autowired
    private TrainTopologyService trainTopologyService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @MockitoBean
    private TrainRepository trainRepository;

    @MockitoBean
    private CoachRepository coachRepository;

    @MockitoBean
    private SeatRepository seatRepository;

    @MockitoBean
    private JourneyRepository journeyRepository;

    @MockitoBean
    private StationRepository stationRepository;

    @MockitoBean
    private JourneyStopRepository journeyStopRepository;

    @MockitoBean
    private TrainStopRepository trainStopRepository;

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    /**
     * Opt-in activation condition. Evaluated before Spring context initialization.
     */
    static boolean isOptedIn() {
        return "true".equalsIgnoreCase(System.getenv("RUN_UPSTASH_SMOKE_TEST"))
                || "true".equalsIgnoreCase(System.getProperty("upstash.smoke.enabled"));
    }

    @BeforeAll
    static void verifyDatabaseSafety(@Autowired DataSource dataSource) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            String url = conn.getMetaData().getURL();
            if (url != null && url.startsWith("jdbc:h2:")) {
                log.info("[SAFETY CHECK PASSED] Connected to safe in-memory H2 database: {}", url);
                return;
            }
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT current_database()")) {
                if (rs.next()) {
                    String currentDb = rs.getString(1);
                    if ("railway_booking".equalsIgnoreCase(currentDb)) {
                        throw new IllegalStateException(
                                "FATAL SAFETY VIOLATION: Test process connected to protected database 'railway_booking'!");
                    }
                }
            }
        }
    }

    @BeforeEach
    void setUp() {
        reset(trainRepository, coachRepository, seatRepository);
    }

    @AfterEach
    void cleanupTrackedRedisKeys() {
        for (String key : trackedKeys) {
            try {
                Boolean deleted = stringRedisTemplate.delete(key);
                log.info("[UPSTASH TOPOLOGY CLEANUP] Deleted test key '{}': {}", key, deleted);
            } catch (Exception ex) {
                log.warn("[UPSTASH TOPOLOGY CLEANUP ERROR] Could not delete key '{}': {}", key, ex.getMessage());
            }
        }
        trackedKeys.clear();
    }

    @Test
    @DisplayName("1. Train layout: populates Upstash Redis cache on miss, serves from cache on hit with ~24h TTL")
    void verifyTrainTopologyPopulatesRedisAndRepeatedRequestHitsCache() {
        Long trainId = 888101L;
        String expectedKey = "trainTopology::train:" + trainId;

        stringRedisTemplate.delete(expectedKey);
        trackedKeys.add(expectedKey);

        Train train = createTrain(trainId, "12951", "Mumbai Rajdhani");
        Coach coach = createCoach(999101L, train, "B1", "AC_3_TIER", 2);
        Seat seat1 = createSeat(777101L, coach, 1, "LOWER");
        Seat seat2 = createSeat(777102L, coach, 2, "UPPER");

        when(trainRepository.findById(trainId)).thenReturn(Optional.of(train));
        when(coachRepository.findByTrain_TrainId(trainId)).thenReturn(List.of(coach));
        when(seatRepository.findByCoach_CoachIdIn(List.of(999101L))).thenReturn(List.of(seat1, seat2));

        // ------------------------------------------------------------------
        // Call 1: Cache Miss -> Must invoke repositories and write to Upstash
        // ------------------------------------------------------------------
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(expectedKey)),
                "Key must not exist in Upstash Redis prior to initial topology lookup");

        TrainTopologyDTO firstResult = trainTopologyService.getTrainTopology(trainId);
        assertNotNull(firstResult);
        assertEquals(trainId, firstResult.trainId());
        assertEquals("12951", firstResult.trainNumber());
        assertEquals("Mumbai Rajdhani", firstResult.trainName());
        assertEquals(1, firstResult.totalCoaches());
        assertEquals(2, firstResult.totalSeats());
        assertEquals(1, firstResult.coaches().size());
        assertEquals("B1", firstResult.coaches().get(0).coachNumber());
        assertEquals(2, firstResult.coaches().get(0).seats().size());

        verify(trainRepository, times(1)).findById(trainId);
        verify(coachRepository, times(1)).findByTrain_TrainId(trainId);
        verify(seatRepository, times(1)).findByCoach_CoachIdIn(List.of(999101L));

        // Verify key exists in live Upstash Redis (WAN tolerant)
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(stringRedisTemplate.hasKey(expectedKey)));

        // Verify TTL is approximately 24 hours (86400s; tolerance 86000s - 86400s)
        Long ttlSeconds = stringRedisTemplate.getExpire(expectedKey, TimeUnit.SECONDS);
        assertNotNull(ttlSeconds, "TTL must be present for cached train topology");
        log.info("[UPSTASH TTL AUDIT] Key: '{}', Remaining TTL: {}s (expected ~86400s)", expectedKey, ttlSeconds);
        assertTrue(ttlSeconds > 86000 && ttlSeconds <= 86400,
                "Train topology TTL should be ~86400 seconds (24h), but was: " + ttlSeconds);

        // Verify serialized JSON contains class metadata and train details
        String rawJson = stringRedisTemplate.opsForValue().get(expectedKey);
        assertNotNull(rawJson);
        assertTrue(rawJson.contains("TrainTopologyDTO"), "Serialized JSON must contain TrainTopologyDTO class type");
        assertTrue(rawJson.contains("12951"), "Serialized JSON must contain trainNumber '12951'");
        assertTrue(rawJson.contains("Mumbai Rajdhani"), "Serialized JSON must contain trainName");

        // ------------------------------------------------------------------
        // Call 2: Cache Hit -> Serves from Upstash Redis without querying DB
        // ------------------------------------------------------------------
        TrainTopologyDTO secondResult = trainTopologyService.getTrainTopology(trainId);
        assertNotNull(secondResult);
        assertEquals(firstResult.trainId(), secondResult.trainId());
        assertEquals(firstResult.trainNumber(), secondResult.trainNumber());
        assertEquals(firstResult.totalCoaches(), secondResult.totalCoaches());
        assertEquals(firstResult.totalSeats(), secondResult.totalSeats());
        assertEquals(firstResult.coaches().size(), secondResult.coaches().size());

        // Invariant: underlying repositories must NOT be invoked again
        verify(trainRepository, times(1)).findById(trainId);
        verify(coachRepository, times(1)).findByTrain_TrainId(trainId);
        verify(seatRepository, times(1)).findByCoach_CoachIdIn(anyList());
        log.info("[UPSTASH CACHE HIT] Successfully retrieved train topology from Upstash Redis without DB access");
    }

    @Test
    @DisplayName("2. Coach layout: populates Upstash Redis cache on miss, serves from cache on hit with ~24h TTL")
    void verifyCoachTopologyPopulatesRedisAndRepeatedRequestHitsCache() {
        Long coachId = 888201L;
        Long trainId = 888200L;
        String expectedKey = "trainTopology::coach:" + coachId;

        stringRedisTemplate.delete(expectedKey);
        trackedKeys.add(expectedKey);

        Train train = createTrain(trainId, "12001", "Bhopal Shatabdi");
        Coach coach = createCoach(coachId, train, "C1", "CHAIR_CAR", 2);
        Seat seat1 = createSeat(777201L, coach, 1, "WINDOW");
        Seat seat2 = createSeat(777202L, coach, 2, "AISLE");

        when(coachRepository.findById(coachId)).thenReturn(Optional.of(coach));
        when(seatRepository.findByCoach_CoachId(coachId)).thenReturn(List.of(seat1, seat2));

        // ------------------------------------------------------------------
        // Call 1: Cache Miss -> Must invoke repositories and write to Upstash
        // ------------------------------------------------------------------
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(expectedKey)));

        CoachTopologyDTO firstResult = trainTopologyService.getCoachTopology(coachId);
        assertNotNull(firstResult);
        assertEquals(coachId, firstResult.coachId());
        assertEquals(trainId, firstResult.trainId());
        assertEquals("C1", firstResult.coachNumber());
        assertEquals("CHAIR_CAR", firstResult.coachType());
        assertEquals(2, firstResult.seatCapacity());
        assertEquals(2, firstResult.seats().size());

        verify(coachRepository, times(1)).findById(coachId);
        verify(seatRepository, times(1)).findByCoach_CoachId(coachId);

        // Verify key exists in live Upstash Redis
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(stringRedisTemplate.hasKey(expectedKey)));

        // Verify TTL is approximately 24 hours (86400s)
        Long ttlSeconds = stringRedisTemplate.getExpire(expectedKey, TimeUnit.SECONDS);
        assertNotNull(ttlSeconds, "TTL must be present for cached coach topology");
        log.info("[UPSTASH TTL AUDIT] Key: '{}', Remaining TTL: {}s (expected ~86400s)", expectedKey, ttlSeconds);
        assertTrue(ttlSeconds > 86000 && ttlSeconds <= 86400,
                "Coach topology TTL should be ~86400 seconds (24h), but was: " + ttlSeconds);

        // Verify serialized JSON contains class metadata and coach details
        String rawJson = stringRedisTemplate.opsForValue().get(expectedKey);
        assertNotNull(rawJson);
        assertTrue(rawJson.contains("CoachTopologyDTO"), "Serialized JSON must contain CoachTopologyDTO class type");
        assertTrue(rawJson.contains("C1"), "Serialized JSON must contain coachNumber 'C1'");

        // ------------------------------------------------------------------
        // Call 2: Cache Hit -> Serves from Upstash Redis without querying DB
        // ------------------------------------------------------------------
        CoachTopologyDTO secondResult = trainTopologyService.getCoachTopology(coachId);
        assertNotNull(secondResult);
        assertEquals(firstResult.coachId(), secondResult.coachId());
        assertEquals(firstResult.coachNumber(), secondResult.coachNumber());
        assertEquals(firstResult.seats().size(), secondResult.seats().size());

        // Invariant: underlying repositories must NOT be invoked again
        verify(coachRepository, times(1)).findById(coachId);
        verify(seatRepository, times(1)).findByCoach_CoachId(coachId);
        log.info("[UPSTASH CACHE HIT] Successfully retrieved coach topology from Upstash Redis without DB access");
    }

    @Test
    @DisplayName("3. Key separation: trains and coaches with identical numeric IDs create separate non-colliding Redis keys")
    void verifyKeySeparationBetweenTrainsAndCoaches() {
        Long sharedId = 888300L;
        String trainKey = "trainTopology::train:" + sharedId;
        String coachKey = "trainTopology::coach:" + sharedId;

        stringRedisTemplate.delete(trainKey);
        stringRedisTemplate.delete(coachKey);
        trackedKeys.add(trainKey);
        trackedKeys.add(coachKey);

        Train train = createTrain(sharedId, "22436", "Vande Bharat Express");
        Coach coach = createCoach(sharedId, train, "E1", "EXECUTIVE_CHAIR_CAR", 52);

        when(trainRepository.findById(sharedId)).thenReturn(Optional.of(train));
        when(coachRepository.findByTrain_TrainId(sharedId)).thenReturn(List.of(coach));
        when(seatRepository.findByCoach_CoachIdIn(List.of(sharedId))).thenReturn(List.of());

        when(coachRepository.findById(sharedId)).thenReturn(Optional.of(coach));
        when(seatRepository.findByCoach_CoachId(sharedId)).thenReturn(List.of());

        // Execute lookups for both train and coach sharing the same numeric ID
        TrainTopologyDTO trainResult = trainTopologyService.getTrainTopology(sharedId);
        CoachTopologyDTO coachResult = trainTopologyService.getCoachTopology(sharedId);

        assertNotNull(trainResult);
        assertNotNull(coachResult);
        assertEquals("22436", trainResult.trainNumber());
        assertEquals("E1", coachResult.coachNumber());

        // Verify both keys exist independently in Upstash Redis
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(stringRedisTemplate.hasKey(trainKey)));
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(stringRedisTemplate.hasKey(coachKey)));

        assertNotEquals(trainKey, coachKey, "Cache keys must not collide");

        // Verify deserialization fidelity from each independent key
        TrainTopologyDTO trainHit = trainTopologyService.getTrainTopology(sharedId);
        CoachTopologyDTO coachHit = trainTopologyService.getCoachTopology(sharedId);
        assertEquals("22436", trainHit.trainNumber());
        assertEquals("E1", coachHit.coachNumber());

        log.info("[UPSTASH KEY SEPARATION] Successfully verified independent cache keys for shared numeric ID: '{}' vs '{}'",
                trainKey, coachKey);
    }

    @Test
    @DisplayName("4. Cache eviction: direct eviction and event-driven invalidation remove keys cleanly from Upstash")
    void verifyTargetedAndEventDrivenEvictionRemovesKeysFromUpstash() {
        Long trainId = 888401L;
        Long coachId = 888402L;
        String trainKey = "trainTopology::train:" + trainId;
        String coachKey = "trainTopology::coach:" + coachId;

        stringRedisTemplate.delete(trainKey);
        stringRedisTemplate.delete(coachKey);
        trackedKeys.add(trainKey);
        trackedKeys.add(coachKey);

        Train train = createTrain(trainId, "12005", "Kalka Shatabdi");
        Coach coach = createCoach(coachId, train, "C2", "CHAIR_CAR", 40);

        when(trainRepository.findById(trainId)).thenReturn(Optional.of(train));
        when(coachRepository.findByTrain_TrainId(trainId)).thenReturn(List.of(coach));
        when(seatRepository.findByCoach_CoachIdIn(List.of(coachId))).thenReturn(List.of());
        when(coachRepository.findById(coachId)).thenReturn(Optional.of(coach));
        when(seatRepository.findByCoach_CoachId(coachId)).thenReturn(List.of());

        // 1. Populate both caches
        trainTopologyService.getTrainTopology(trainId);
        trainTopologyService.getCoachTopology(coachId);

        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(stringRedisTemplate.hasKey(trainKey))
                        && Boolean.TRUE.equals(stringRedisTemplate.hasKey(coachKey)));

        // 2. Direct train eviction: removes train key while leaving coach key intact
        trainTopologyService.evictTrainTopology(trainId);

        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> !Boolean.TRUE.equals(stringRedisTemplate.hasKey(trainKey)));
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey(coachKey)),
                "Coach key must remain in Redis after train-only eviction");
        log.info("[UPSTASH DIRECT EVICTION] Successfully verified targeted train eviction");

        // 3. Direct coach eviction
        trainTopologyService.evictCoachTopology(coachId);
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> !Boolean.TRUE.equals(stringRedisTemplate.hasKey(coachKey)));
        log.info("[UPSTASH DIRECT EVICTION] Successfully verified targeted coach eviction");

        // 4. Repopulate both keys for event-driven invalidation verification
        trainTopologyService.getTrainTopology(trainId);
        trainTopologyService.getCoachTopology(coachId);
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(stringRedisTemplate.hasKey(trainKey))
                        && Boolean.TRUE.equals(stringRedisTemplate.hasKey(coachKey)));

        // 5. Event-driven eviction via ApplicationEventPublisher:
        // TrainTopologyServiceImpl listens with fallbackExecution = true, allowing event processing
        eventPublisher.publishEvent(new TrainTopologyChangedEvent(trainId, coachId));

        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> !Boolean.TRUE.equals(stringRedisTemplate.hasKey(trainKey))
                        && !Boolean.TRUE.equals(stringRedisTemplate.hasKey(coachKey)));
        log.info("[UPSTASH EVENT EVICTION] Successfully verified event-driven invalidation via TrainTopologyChangedEvent");
    }

    @Test
    @DisplayName("5. Resilience & Scope Safety: Fail-open topology handler active, dynamic seat availability excluded")
    void verifyFailOpenResilienceAndDynamicSeatExclusion() {
        // 1. Audit configured caches: dynamic availability and inventory must NEVER exist in Redis
        Collection<String> cacheNames = cacheManager.getCacheNames();
        log.info("[CACHE NAMES AUDIT] Active configured caches: {}", cacheNames);
        assertTrue(cacheNames.contains(RedisCacheConfig.TRAIN_TOPOLOGY_CACHE), "trainTopology must be configured");
        assertTrue(cacheNames.contains(RedisCacheConfig.JOURNEY_SEARCH_CACHE), "journeySearch must be configured");
        assertTrue(cacheNames.contains(RedisCacheConfig.TRAIN_SEARCH_CACHE), "trainSearch must be configured");
        assertFalse(cacheNames.contains("seatAvailability"), "Dynamic seat availability must NEVER be cached in Redis");
        assertFalse(cacheNames.contains("seats"), "Seat status must NEVER be cached in Redis");
        assertFalse(cacheNames.contains("bookingInventory"), "Inventory must NEVER be cached in Redis");

        // 2. Verify fail-open cache error handler swallows exceptions for topology cache operations
        RedisCacheConfig.ResilientCacheErrorHandler errorHandler = new RedisCacheConfig.ResilientCacheErrorHandler();
        Cache mockCache = mock(Cache.class);
        when(mockCache.getName()).thenReturn(RedisCacheConfig.TRAIN_TOPOLOGY_CACHE);

        RedisConnectionFailureException connectionException =
                new RedisConnectionFailureException("Simulated Redis outage on trainTopology read");
        RedisSystemException timeoutException =
                new RedisSystemException("Simulated Redis timeout on trainTopology write", new RuntimeException());

        assertDoesNotThrow(() -> errorHandler.handleCacheGetError(connectionException, mockCache, "train:888999"),
                "handleCacheGetError must fail open (swallow exception and allow DB query)");

        assertDoesNotThrow(() -> errorHandler.handleCachePutError(timeoutException, mockCache, "train:888999", mock(TrainTopologyDTO.class)),
                "handleCachePutError must fail open (swallow exception)");

        assertDoesNotThrow(() -> errorHandler.handleCacheEvictError(connectionException, mockCache, "train:888999"),
                "handleCacheEvictError must fail open");

        assertDoesNotThrow(() -> errorHandler.handleCacheClearError(connectionException, mockCache),
                "handleCacheClearError must fail open");

        log.info("[RESILIENCE AUDIT SUCCESS] Topology fail-open handler verified; dynamic seat availability excluded");
    }

    // -------------------------------------------------------------------------
    // Helper Factories
    // -------------------------------------------------------------------------

    private Train createTrain(Long id, String number, String name) {
        Train train = new Train();
        train.setTrainId(id);
        train.setTrainNumber(number);
        train.setTrainName(name);
        return train;
    }

    private Coach createCoach(Long id, Train train, String number, String type, int capacity) {
        Coach coach = new Coach();
        coach.setCoachId(id);
        coach.setTrain(train);
        coach.setCoachNumber(number);
        coach.setCoachType(type);
        coach.setSeatCapacity(capacity);
        return coach;
    }

    private Seat createSeat(Long id, Coach coach, int seatNumber, String seatType) {
        Seat seat = new Seat();
        seat.setSeatId(id);
        seat.setCoach(coach);
        seat.setSeatNumber(seatNumber);
        seat.setSeatType(seatType);
        return seat;
    }
}
