package in.yograj.learnspringboot.railway_booking.integration.redis;

import in.yograj.learnspringboot.railway_booking.config.RedisCacheConfig;
import in.yograj.learnspringboot.railway_booking.dto.JourneyResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainSearchResponseDTO;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.QueueUnavailableException;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.JourneyRepository;
import in.yograj.learnspringboot.railway_booking.repository.JourneyStopRepository;
import in.yograj.learnspringboot.railway_booking.repository.StationRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainStopRepository;
import in.yograj.learnspringboot.railway_booking.service.JourneyService;
import in.yograj.learnspringboot.railway_booking.service.RedisTatkalQueueStore;
import in.yograj.learnspringboot.railway_booking.service.TatkalAdmissionPassService;
import in.yograj.learnspringboot.railway_booking.service.TatkalJourneyPolicy;
import in.yograj.learnspringboot.railway_booking.service.TatkalQueueService;
import in.yograj.learnspringboot.railway_booking.service.TrainSearchService;
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
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Phase 1: Real Upstash Redis Search Cache Integration Verification.
 *
 * Verifies that Railora's Journey Search and Train Search caching implementations
 * operate correctly with the live Upstash Redis instance (over TLS).
 *
 * Verification Scope:
 * 1. Journey search populates actual Redis cache ("journeySearch::<from>_<to>_<date>").
 * 2. Repeated journey search produces a cache hit without querying underlying repositories.
 * 3. Train search populates actual Redis cache ("trainSearch::<from>_<to>").
 * 4. Repeated train search produces a cache hit without querying underlying repositories.
 * 5. Distinct search parameters create separate cache keys without collision.
 * 6. Configured TTL values match expected durations:
 *    - Journey search: ~10 minutes (~600 seconds)
 *    - Train search: ~30 minutes (~1800 seconds)
 * 7. Polymorphic JSON serialization/deserialization with JavaTime types.
 * 8. Cache eviction removes entries cleanly from Upstash Redis.
 * 9. Fail-open resilience: Redis read/write errors fall back to database without disruption.
 * 10. Dynamic seat availability is strictly excluded from Redis caching.
 * 11. Tatkal fail-closed semantics remain untouched.
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
        "DB_URL=jdbc:h2:mem:upstash-search-cache-it;DB_CLOSE_DELAY=-1",
        "DB_USERNAME=sa",
        "DB_PASSWORD=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:upstash-search-cache-it;DB_CLOSE_DELAY=-1",
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
class UpstashSearchCacheIT {

    private static final Logger log = LoggerFactory.getLogger(UpstashSearchCacheIT.class);

    // Track all Redis keys created by this test run for isolated, guaranteed cleanup
    private final Set<String> trackedKeys = ConcurrentHashMap.newKeySet();

    @Autowired
    private JourneyService journeyService;

    @Autowired
    private TrainSearchService trainSearchService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private Environment environment;

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
        reset(journeyRepository, stationRepository, journeyStopRepository, trainStopRepository);
        when(stationRepository.existsById(anyLong())).thenReturn(true);
    }

    @AfterEach
    void cleanupTrackedRedisKeys() {
        for (String key : trackedKeys) {
            try {
                Boolean deleted = stringRedisTemplate.delete(key);
                log.info("[UPSTASH SEARCH CLEANUP] Deleted test key '{}': {}", key, deleted);
            } catch (Exception ex) {
                log.warn("[UPSTASH SEARCH CLEANUP ERROR] Could not delete key '{}': {}", key, ex.getMessage());
            }
        }
        trackedKeys.clear();
    }

    @Test
    @DisplayName("1. Journey search: populates Upstash Redis cache on miss, serves from cache on hit with ~10m TTL")
    void verifyJourneySearchPopulatesRedisCacheAndRepeatedSearchHitsCache() {
        Long fromStationId = 988001L;
        Long toStationId = 988002L;
        LocalDate journeyDate = LocalDate.of(2099, 11, 15);
        String expectedCacheKey = "journeySearch::" + fromStationId + "_" + toStationId + "_" + journeyDate;

        // Ensure clean state for this key
        stringRedisTemplate.delete(expectedCacheKey);
        trackedKeys.add(expectedCacheKey);

        Journey testJourney = createSampleJourney(701L, fromStationId, "NDLS", "New Delhi",
                toStationId, "BCT", "Mumbai Central", journeyDate, "12951", "Mumbai Rajdhani");
        List<JourneyStop> stops = createSampleJourneyStops(testJourney, fromStationId, toStationId, journeyDate);

        when(journeyRepository.findByJourneyDate(journeyDate)).thenReturn(List.of(testJourney));
        when(journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(701L)).thenReturn(stops);

        // ------------------------------------------------------------------
        // Call 1: Cache Miss -> Must invoke JourneyRepository and write to Upstash
        // ------------------------------------------------------------------
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(expectedCacheKey)),
                "Key must not exist in Upstash Redis before first query");

        List<JourneyResponseDTO> firstResult = journeyService.searchJourneys(fromStationId, toStationId, journeyDate);
        assertNotNull(firstResult);
        assertEquals(1, firstResult.size());
        assertEquals("12951", firstResult.get(0).trainNumber());
        assertEquals("NDLS", firstResult.get(0).fromStationCode());
        assertEquals("BCT", firstResult.get(0).toStationCode());

        // Verify repository was called exactly once
        verify(journeyRepository, times(1)).findByJourneyDate(journeyDate);

        // Verify key was written to real Upstash Redis (with WAN-tolerant poll)
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(stringRedisTemplate.hasKey(expectedCacheKey)));

        // Verify TTL is approximately 10 minutes (between 500 and 600 seconds)
        Long ttlSeconds = stringRedisTemplate.getExpire(expectedCacheKey, TimeUnit.SECONDS);
        assertNotNull(ttlSeconds, "TTL must be present for cached journey search");
        log.info("[UPSTASH TTL AUDIT] Key: '{}', Remaining TTL: {}s (expected ~600s)", expectedCacheKey, ttlSeconds);
        assertTrue(ttlSeconds > 500 && ttlSeconds <= 600,
                "Journey search TTL should be ~600 seconds (10 min), but was: " + ttlSeconds);

        // Verify serialized JSON payload fidelity
        String rawJson = stringRedisTemplate.opsForValue().get(expectedCacheKey);
        assertNotNull(rawJson);
        assertTrue(rawJson.contains("12951"), "Cached JSON in Upstash must contain trainNumber '12951'");
        assertTrue(rawJson.contains("Mumbai Rajdhani"), "Cached JSON in Upstash must contain trainName");

        // ------------------------------------------------------------------
        // Call 2: Cache Hit -> Must serve from Upstash Redis without querying DB
        // ------------------------------------------------------------------
        List<JourneyResponseDTO> secondResult = journeyService.searchJourneys(fromStationId, toStationId, journeyDate);
        assertNotNull(secondResult);
        assertEquals(1, secondResult.size());
        assertEquals(firstResult.get(0).journeyId(), secondResult.get(0).journeyId());
        assertEquals(firstResult.get(0).trainNumber(), secondResult.get(0).trainNumber());
        assertEquals(firstResult.get(0).departureTime(), secondResult.get(0).departureTime());
        assertEquals(firstResult.get(0).arrivalTime(), secondResult.get(0).arrivalTime());

        // CRITICAL: Repository call count MUST remain 1 (no DB hit on repeated search)
        verify(journeyRepository, times(1)).findByJourneyDate(journeyDate);
        log.info("[UPSTASH CACHE HIT] Successfully retrieved journey search from Upstash Redis without DB access");
    }

    @Test
    @DisplayName("2. Journey search: different parameters generate separate cache entries in Upstash Redis")
    void verifyJourneySearchKeySeparationForDifferentParameters() {
        Long fromStationId = 988001L;
        Long toStationId = 988002L;
        LocalDate dateA = LocalDate.of(2099, 11, 20);
        LocalDate dateB = LocalDate.of(2099, 11, 21);

        String keyA = "journeySearch::" + fromStationId + "_" + toStationId + "_" + dateA;
        String keyB = "journeySearch::" + fromStationId + "_" + toStationId + "_" + dateB;

        stringRedisTemplate.delete(keyA);
        stringRedisTemplate.delete(keyB);
        trackedKeys.add(keyA);
        trackedKeys.add(keyB);

        Journey journeyA = createSampleJourney(702L, fromStationId, "NDLS", "New Delhi",
                toStationId, "BCT", "Mumbai Central", dateA, "12001", "Bhopal Shatabdi");
        Journey journeyB = createSampleJourney(703L, fromStationId, "NDLS", "New Delhi",
                toStationId, "BCT", "Mumbai Central", dateB, "12002", "New Delhi Shatabdi");

        when(journeyRepository.findByJourneyDate(dateA)).thenReturn(List.of(journeyA));
        when(journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(702L))
                .thenReturn(createSampleJourneyStops(journeyA, fromStationId, toStationId, dateA));

        when(journeyRepository.findByJourneyDate(dateB)).thenReturn(List.of(journeyB));
        when(journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(703L))
                .thenReturn(createSampleJourneyStops(journeyB, fromStationId, toStationId, dateB));

        // Execute searches for two different dates
        List<JourneyResponseDTO> resultA = journeyService.searchJourneys(fromStationId, toStationId, dateA);
        List<JourneyResponseDTO> resultB = journeyService.searchJourneys(fromStationId, toStationId, dateB);

        assertEquals("12001", resultA.get(0).trainNumber());
        assertEquals("12002", resultB.get(0).trainNumber());

        // Verify both distinct keys exist independently in Upstash Redis
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(stringRedisTemplate.hasKey(keyA)));
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(stringRedisTemplate.hasKey(keyB)));
        assertNotEquals(keyA, keyB, "Cache keys must not collide");

        log.info("[UPSTASH KEY SEPARATION] Successfully verified independent cache entries for distinct dates: '{}' and '{}'",
                keyA, keyB);
    }

    @Test
    @DisplayName("3. Train search: populates Upstash Redis cache on miss, serves from cache on hit with ~30m TTL")
    void verifyTrainSearchPopulatesRedisCacheAndRepeatedSearchHitsCache() {
        String fromCode = "TSTNDLS";
        String toCode = "TSTBCT";
        String expectedCacheKey = "trainSearch::" + fromCode + "_" + toCode;

        // Ensure clean state
        stringRedisTemplate.delete(expectedCacheKey);
        trackedKeys.add(expectedCacheKey);

        Train train = new Train();
        train.setTrainId(801L);
        train.setTrainNumber("12953");
        train.setTrainName("August Kranti Tejas Rajdhani");

        TrainRoute route = new TrainRoute();
        route.setTrain(train);

        Station stFrom = new Station();
        stFrom.setStationCode(fromCode);
        stFrom.setStationName("Test New Delhi");

        Station stTo = new Station();
        stTo.setStationCode(toCode);
        stTo.setStationName("Test Mumbai Central");

        TrainSearchResponseDTO dto = new TrainSearchResponseDTO(
                801L, "12953", "August Kranti Rajdhani",
                fromCode, "Test From Station", null, LocalTime.of(17, 15), 0,
                toCode, "Test To Station", LocalTime.of(10, 5), null, 1
        );
        when(trainStopRepository.findTrainStopsBetweenStations(fromCode, toCode))
                .thenReturn(List.of(dto));

        // ------------------------------------------------------------------
        // Call 1: Cache Miss -> Queries repository and populates Upstash Redis
        // ------------------------------------------------------------------
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(expectedCacheKey)));

        List<TrainSearchResponseDTO> firstResult = trainSearchService.searchTrains(fromCode, toCode);
        assertNotNull(firstResult);
        assertEquals(1, firstResult.size());
        assertEquals("12953", firstResult.get(0).trainNumber());

        verify(trainStopRepository, times(1)).findTrainStopsBetweenStations(fromCode, toCode);

        // Verify key exists in Upstash Redis
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(stringRedisTemplate.hasKey(expectedCacheKey)));

        // Verify TTL is approximately 30 minutes (between 1700 and 1800 seconds)
        Long ttlSeconds = stringRedisTemplate.getExpire(expectedCacheKey, TimeUnit.SECONDS);
        assertNotNull(ttlSeconds, "TTL must be present for cached train search");
        log.info("[UPSTASH TTL AUDIT] Key: '{}', Remaining TTL: {}s (expected ~1800s)", expectedCacheKey, ttlSeconds);
        assertTrue(ttlSeconds > 1700 && ttlSeconds <= 1800,
                "Train search TTL should be ~1800 seconds (30 min), but was: " + ttlSeconds);

        // ------------------------------------------------------------------
        // Call 2: Cache Hit -> Case-insensitive and whitespace normalized
        // ------------------------------------------------------------------
        List<TrainSearchResponseDTO> secondResult = trainSearchService.searchTrains(
                "  " + fromCode.toLowerCase() + "  ", "  " + toCode.toLowerCase() + "  ");
        assertNotNull(secondResult);
        assertEquals(1, secondResult.size());
        assertEquals(firstResult.get(0).trainNumber(), secondResult.get(0).trainNumber());

        // Repository must NOT be called again
        verify(trainStopRepository, times(1)).findTrainStopsBetweenStations(fromCode, toCode);
        log.info("[UPSTASH CACHE HIT] Successfully retrieved train search from Upstash Redis with normalized key");
    }

    @Test
    @DisplayName("4. Train search: different routes create separate cache entries in Upstash Redis")
    void verifyTrainSearchKeySeparationForDifferentRoutes() {
        String route1From = "TSTA";
        String route1To = "TSTB";
        String route2From = "TSTA";
        String route2To = "TSTC";

        String key1 = "trainSearch::" + route1From + "_" + route1To;
        String key2 = "trainSearch::" + route2From + "_" + route2To;

        stringRedisTemplate.delete(key1);
        stringRedisTemplate.delete(key2);
        trackedKeys.add(key1);
        trackedKeys.add(key2);

        Train train1 = new Train();
        train1.setTrainId(901L);
        train1.setTrainNumber("22436");
        train1.setTrainName("Vande Bharat Express");

        TrainRoute route1 = new TrainRoute();
        route1.setTrain(train1);

        Station stA = new Station(); stA.setStationCode(route1From); stA.setStationName("Station A");
        Station stB = new Station(); stB.setStationCode(route1To); stB.setStationName("Station B");
        Station stC = new Station(); stC.setStationCode(route2To); stC.setStationName("Station C");

        TrainStop stop1A = new TrainStop(); stop1A.setTrainRoute(route1); stop1A.setStation(stA);
        stop1A.setDepartureTime(LocalTime.of(6, 0)); stop1A.setDayOffset(0);
        TrainStop stop1B = new TrainStop(); stop1B.setTrainRoute(route1); stop1B.setStation(stB);
        stop1B.setArrivalTime(LocalTime.of(14, 0)); stop1B.setDayOffset(0);

        Train train2 = new Train();
        train2.setTrainId(902L);
        train2.setTrainNumber("22437");
        train2.setTrainName("Tejas Express");

        TrainRoute route2 = new TrainRoute();
        route2.setTrain(train2);

        TrainStop stop2A = new TrainStop(); stop2A.setTrainRoute(route2); stop2A.setStation(stA);
        stop2A.setDepartureTime(LocalTime.of(7, 0)); stop2A.setDayOffset(0);
        TrainStop stop2C = new TrainStop(); stop2C.setTrainRoute(route2); stop2C.setStation(stC);
        stop2C.setArrivalTime(LocalTime.of(15, 0)); stop2C.setDayOffset(0);

        TrainSearchResponseDTO dto1 = new TrainSearchResponseDTO(
                901L, "22436", "Vande Bharat Express",
                route1From, "Station A", null, LocalTime.of(6, 0), 0,
                route1To, "Station B", LocalTime.of(14, 0), null, 0
        );
        TrainSearchResponseDTO dto2 = new TrainSearchResponseDTO(
                902L, "22437", "Tejas Express",
                route2From, "Station A", null, LocalTime.of(7, 0), 0,
                route2To, "Station C", LocalTime.of(15, 0), null, 0
        );
        when(trainStopRepository.findTrainStopsBetweenStations(route1From, route1To))
                .thenReturn(List.of(dto1));
        when(trainStopRepository.findTrainStopsBetweenStations(route2From, route2To))
                .thenReturn(List.of(dto2));

        List<TrainSearchResponseDTO> res1 = trainSearchService.searchTrains(route1From, route1To);
        List<TrainSearchResponseDTO> res2 = trainSearchService.searchTrains(route2From, route2To);

        assertEquals("22436", res1.get(0).trainNumber());
        assertEquals("22437", res2.get(0).trainNumber());

        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(stringRedisTemplate.hasKey(key1)));
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(stringRedisTemplate.hasKey(key2)));
        assertNotEquals(key1, key2);

        log.info("[UPSTASH KEY SEPARATION] Successfully verified independent train search cache entries in Upstash");
    }

    @Test
    @DisplayName("5. Cache eviction: evicting a search entry cleans it from Upstash Redis")
    void verifyCacheEvictionRemovesSearchKeyFromUpstash() {
        Long fromId = 988010L;
        Long toId = 988020L;
        LocalDate date = LocalDate.of(2099, 12, 25);
        String subKey = fromId + "_" + toId + "_" + date;
        String fullKey = "journeySearch::" + subKey;

        stringRedisTemplate.delete(fullKey);
        trackedKeys.add(fullKey);

        Journey journey = createSampleJourney(710L, fromId, "STNA", "Station A",
                toId, "STNB", "Station B", date, "12005", "Kalka Shatabdi");
        when(journeyRepository.findByJourneyDate(date)).thenReturn(List.of(journey));
        when(journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(710L))
                .thenReturn(createSampleJourneyStops(journey, fromId, toId, date));

        // Populate cache
        journeyService.searchJourneys(fromId, toId, date);
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(stringRedisTemplate.hasKey(fullKey)));

        // Evict specific entry via CacheManager
        Cache cache = cacheManager.getCache(RedisCacheConfig.JOURNEY_SEARCH_CACHE);
        assertNotNull(cache);
        cache.evict(subKey);

        // Verify key is immediately removed from real Upstash Redis
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> !Boolean.TRUE.equals(stringRedisTemplate.hasKey(fullKey)));
        log.info("[UPSTASH EVICTION] Successfully verified individual key eviction from Upstash Redis");
    }

    @Test
    @DisplayName("6. Resilience & Safety: Fail-open search handler active, dynamic seat availability excluded, Tatkal fail-closed intact")
    void verifyFailOpenResilienceAndDynamicSeatExclusion() {
        // 1. Verify configured cache namespaces: dynamic seat availability must NEVER be cached
        Collection<String> cacheNames = cacheManager.getCacheNames();
        log.info("[CACHE NAMES AUDIT] Active configured caches: {}", cacheNames);
        assertTrue(cacheNames.contains(RedisCacheConfig.JOURNEY_SEARCH_CACHE), "journeySearch must be configured");
        assertTrue(cacheNames.contains(RedisCacheConfig.TRAIN_SEARCH_CACHE), "trainSearch must be configured");
        assertFalse(cacheNames.contains("seatAvailability"), "Dynamic seat availability must NEVER be cached in Redis");
        assertFalse(cacheNames.contains("seats"), "Seat status must NEVER be cached in Redis");
        assertFalse(cacheNames.contains("bookingInventory"), "Inventory must NEVER be cached in Redis");

        // 2. Verify fail-open error handler swallows Redis errors for search read paths
        RedisCacheConfig.ResilientCacheErrorHandler errorHandler = new RedisCacheConfig.ResilientCacheErrorHandler();
        Cache mockCache = mock(Cache.class);
        when(mockCache.getName()).thenReturn(RedisCacheConfig.JOURNEY_SEARCH_CACHE);

        RedisConnectionFailureException connectionFailure =
                new RedisConnectionFailureException("Simulated Redis outage during verification");

        assertDoesNotThrow(() -> errorHandler.handleCacheGetError(connectionFailure, mockCache, "testKey"),
                "handleCacheGetError must fail open (swallow exception and allow DB query)");

        assertDoesNotThrow(() -> errorHandler.handleCachePutError(connectionFailure, mockCache, "testKey", "testVal"),
                "handleCachePutError must fail open (swallow exception)");

        assertDoesNotThrow(() -> errorHandler.handleCacheEvictError(connectionFailure, mockCache, "testKey"),
                "handleCacheEvictError must fail open");

        // 3. Verify Tatkal queue fails CLOSED on Redis connection failure (CRITICAL SECURITY INVARIANT)
        RedisTatkalQueueStore mockTatkalStore = mock(RedisTatkalQueueStore.class);
        TatkalAdmissionPassService mockPassService = mock(TatkalAdmissionPassService.class);
        JourneyRepository mockJourneyRepo = mock(JourneyRepository.class);
        TatkalJourneyPolicy policy = new TatkalJourneyPolicy("1");

        TatkalQueueService tatkalQueueService = new TatkalQueueService(
                mockTatkalStore, mockPassService, mockJourneyRepo, policy);
        org.springframework.test.util.ReflectionTestUtils.setField(tatkalQueueService, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(tatkalQueueService, "capacity", 10);
        org.springframework.test.util.ReflectionTestUtils.setField(tatkalQueueService, "admissionTtlSeconds", 300L);
        org.springframework.test.util.ReflectionTestUtils.setField(tatkalQueueService, "entryTtlSeconds", 86400L);

        when(mockJourneyRepo.existsById(1L)).thenReturn(true);
        when(mockTatkalStore.incrementRateLimit(anyLong(), anyString(), anyString(), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("Redis connection pool timeout"));

        assertThrows(QueueUnavailableException.class, () -> tatkalQueueService.join(1L, "passenger1"),
                "Tatkal queue operations MUST fail closed (503 QueueUnavailableException), NEVER fail open");

        log.info("[RESILIENCE AUDIT SUCCESS] Fail-open search, dynamic seat exclusion, and Tatkal fail-closed confirmed");
    }

    // -------------------------------------------------------------------------
    // Helper Factories
    // -------------------------------------------------------------------------

    private Journey createSampleJourney(Long journeyId, Long fromStationId, String fromCode, String fromName,
                                        Long toStationId, String toCode, String toName,
                                        LocalDate journeyDate, String trainNumber, String trainName) {
        Station from = new Station();
        from.setStationId(fromStationId);
        from.setStationCode(fromCode);
        from.setStationName(fromName);

        Station to = new Station();
        to.setStationId(toStationId);
        to.setStationCode(toCode);
        to.setStationName(toName);

        Train train = new Train();
        train.setTrainId(journeyId + 1000);
        train.setTrainNumber(trainNumber);
        train.setTrainName(trainName);

        Journey journey = new Journey();
        journey.setJourneyId(journeyId);
        journey.setTrain(train);
        journey.setFromStation(from);
        journey.setToStation(to);
        journey.setJourneyDate(journeyDate);
        journey.setDepartureTime(LocalTime.of(16, 55));
        journey.setArrivalTime(LocalTime.of(8, 35));
        return journey;
    }

    private List<JourneyStop> createSampleJourneyStops(Journey journey, Long fromStationId, Long toStationId, LocalDate journeyDate) {
        JourneyStop originStop = new JourneyStop();
        originStop.setJourneyStopId(journey.getJourneyId() * 10 + 1);
        originStop.setJourney(journey);
        originStop.setStation(journey.getFromStation());
        originStop.setStopSequence(1);
        originStop.setDepartureAt(LocalDateTime.of(journeyDate, LocalTime.of(16, 55)));

        JourneyStop destStop = new JourneyStop();
        destStop.setJourneyStopId(journey.getJourneyId() * 10 + 2);
        destStop.setJourney(journey);
        destStop.setStation(journey.getToStation());
        destStop.setStopSequence(2);
        destStop.setArrivalAt(LocalDateTime.of(journeyDate.plusDays(1), LocalTime.of(8, 35)));

        return List.of(originStop, destStop);
    }
}
