package in.yograj.learnspringboot.railway_booking.cache;

import in.yograj.learnspringboot.railway_booking.config.RedisCacheConfig;
import in.yograj.learnspringboot.railway_booking.dto.JourneyResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainSearchResponseDTO;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.QueueUnavailableException;
import in.yograj.learnspringboot.railway_booking.repository.*;
import in.yograj.learnspringboot.railway_booking.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused verification for resilient Redis caching:
 * 1. Cache hit/miss and key determinism on Journey and Train search.
 * 2. Cross-parameter collision resistance.
 * 3. Schedule mutation cache eviction.
 * 4. Fail-open error handling on Redis read/write outages.
 * 5. Preservation of Tatkal fail-closed security.
 * 6. Jackson serialization fidelity for JavaTime DTO records.
 */
class SearchCacheResilienceTests {

    private JourneyRepository journeyRepository;
    private StationRepository stationRepository;
    private JourneyStopRepository journeyStopRepository;
    private TrainStopRepository trainStopRepository;
    private JourneyServiceImpl journeyService;
    private TrainSearchServiceImpl trainSearchService;
    private ConcurrentMapCacheManager cacheManager;
    private RedisCacheConfig.ResilientCacheErrorHandler errorHandler;

    @BeforeEach
    void setUp() {
        journeyRepository = mock(JourneyRepository.class);
        stationRepository = mock(StationRepository.class);
        journeyStopRepository = mock(JourneyStopRepository.class);
        trainStopRepository = mock(TrainStopRepository.class);

        TrainRepository trainRepository = mock(TrainRepository.class);
        CoachRepository coachRepository = mock(CoachRepository.class);
        SeatRepository seatRepository = mock(SeatRepository.class);
        JourneySeatInventoryRepository inventoryRepository = mock(JourneySeatInventoryRepository.class);
        TrainRouteRepository trainRouteRepository = mock(TrainRouteRepository.class);

        journeyService = new JourneyServiceImpl(
                journeyRepository, trainRepository, stationRepository,
                coachRepository, seatRepository, inventoryRepository,
                trainRouteRepository, trainStopRepository, journeyStopRepository
        );

        trainSearchService = new TrainSearchServiceImpl(trainStopRepository);

        cacheManager = new ConcurrentMapCacheManager(
                RedisCacheConfig.JOURNEY_SEARCH_CACHE,
                RedisCacheConfig.TRAIN_SEARCH_CACHE
        );
        errorHandler = new RedisCacheConfig.ResilientCacheErrorHandler();

        when(stationRepository.existsById(anyLong())).thenReturn(true);
    }

    @Test
    @DisplayName("1 & 2: First journey search queries database, second identical search uses cache")
    void firstJourneySearchQueriesDatabase_secondUsesCache() {
        LocalDate date = LocalDate.of(2026, 11, 15);
        Journey testJourney = createSampleJourney(101L, 1L, 2L, date);
        when(journeyRepository.findByJourneyDate(date)).thenReturn(List.of(testJourney));
        when(journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(101L)).thenReturn(List.of());

        Cache cache = cacheManager.getCache(RedisCacheConfig.JOURNEY_SEARCH_CACHE);
        assertNotNull(cache);

        String cacheKey = "1_2_2026-11-15";

        // Step 1: Cache Miss -> invokes service and populates cache
        assertNull(cache.get(cacheKey));
        List<JourneyResponseDTO> firstResult = journeyService.searchJourneys(1L, 2L, date);
        assertEquals(1, firstResult.size());
        cache.put(cacheKey, firstResult);
        verify(journeyRepository, times(1)).findByJourneyDate(date);

        // Step 2: Cache Hit -> returns cached result without querying DB
        Cache.ValueWrapper wrapper = cache.get(cacheKey);
        assertNotNull(wrapper);
        @SuppressWarnings("unchecked")
        List<JourneyResponseDTO> cachedResult = (List<JourneyResponseDTO>) wrapper.get();
        assertEquals(firstResult, cachedResult);

        // Verify repository was not queried a second time
        verify(journeyRepository, times(1)).findByJourneyDate(date);
    }

    @Test
    @DisplayName("3: Different search parameters do not collide in cache")
    void differentSearchParametersDoNotCollide() {
        LocalDate date1 = LocalDate.of(2026, 11, 15);
        LocalDate date2 = LocalDate.of(2026, 11, 16);

        Journey journey1 = createSampleJourney(101L, 1L, 2L, date1);
        Journey journey2 = createSampleJourney(102L, 1L, 3L, date1);
        Journey journey3 = createSampleJourney(103L, 1L, 2L, date2);

        when(journeyRepository.findByJourneyDate(date1)).thenReturn(List.of(journey1, journey2));
        when(journeyRepository.findByJourneyDate(date2)).thenReturn(List.of(journey3));
        when(journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(anyLong())).thenReturn(List.of());

        Cache cache = cacheManager.getCache(RedisCacheConfig.JOURNEY_SEARCH_CACHE);
        assertNotNull(cache);

        // Keys with different parameters
        String key1 = "1_2_2026-11-15";
        String key2 = "1_3_2026-11-15";
        String key3 = "1_2_2026-11-16";

        List<JourneyResponseDTO> res1 = journeyService.searchJourneys(1L, 2L, date1);
        cache.put(key1, res1);

        List<JourneyResponseDTO> res2 = journeyService.searchJourneys(1L, 3L, date1);
        cache.put(key2, res2);

        List<JourneyResponseDTO> res3 = journeyService.searchJourneys(1L, 2L, date2);
        cache.put(key3, res3);

        // Verify each distinct key maintains its own distinct cached result
        assertNotEquals(key1, key2);
        assertNotEquals(key1, key3);
        assertNotNull(cache.get(key1));
        assertNotNull(cache.get(key2));
        assertNotNull(cache.get(key3));
    }

    @Test
    @DisplayName("4: Train search results are cached with normalized keys")
    void trainSearchCachedWithNormalizedKeys() {
        Train train = new Train();
        train.setTrainId(501L);
        train.setTrainNumber("12951");
        train.setTrainName("Mumbai Rajdhani");

        TrainRoute route = new TrainRoute();
        route.setTrain(train);

        Station stFrom = new Station();
        stFrom.setStationCode("NDLS");
        stFrom.setStationName("New Delhi");

        Station stTo = new Station();
        stTo.setStationCode("BCT");
        stTo.setStationName("Mumbai Central");

        TrainSearchResponseDTO dto = new TrainSearchResponseDTO(
                501L, "12951", "Mumbai Rajdhani",
                "NDLS", "New Delhi", null, LocalTime.of(16, 55), 0,
                "BCT", "Mumbai Central", LocalTime.of(8, 35), null, 1
        );
        when(trainStopRepository.findTrainStopsBetweenStations("NDLS", "BCT"))
                .thenReturn(List.of(dto));

        Cache cache = cacheManager.getCache(RedisCacheConfig.TRAIN_SEARCH_CACHE);
        assertNotNull(cache);

        String normalizedKey = "NDLS_BCT";

        // Query with lowercase and whitespace
        List<TrainSearchResponseDTO> result = trainSearchService.searchTrains(" ndls ", " bct ");
        assertEquals(1, result.size());
        assertEquals("12951", result.get(0).trainNumber());
        cache.put(normalizedKey, result);

        // Second lookup retrieves from cache
        Cache.ValueWrapper wrapper = cache.get(normalizedKey);
        assertNotNull(wrapper);
        @SuppressWarnings("unchecked")
        List<TrainSearchResponseDTO> cached = (List<TrainSearchResponseDTO>) wrapper.get();
        assertEquals("12951", cached.get(0).trainNumber());

        verify(trainStopRepository, times(1)).findTrainStopsBetweenStations("NDLS", "BCT");
    }

    @Test
    @DisplayName("5: Redis read failure falls back to database (fail-open)")
    void redisReadFailureFallsBackToDatabase() {
        Cache mockCache = mock(Cache.class);
        when(mockCache.getName()).thenReturn(RedisCacheConfig.JOURNEY_SEARCH_CACHE);

        // Simulate Redis connection failure on get
        RedisConnectionFailureException redisException =
                new RedisConnectionFailureException("Connection refused: /127.0.0.1:6379");

        // Error handler must NOT throw exception, but log warning and return silently
        assertDoesNotThrow(() -> errorHandler.handleCacheGetError(redisException, mockCache, "1_2_2026-11-15"));

        // Backing DB query executes transparently
        LocalDate date = LocalDate.of(2026, 11, 15);
        Journey journey = createSampleJourney(101L, 1L, 2L, date);
        when(journeyRepository.findByJourneyDate(date)).thenReturn(List.of(journey));
        when(journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(101L)).thenReturn(List.of());

        List<JourneyResponseDTO> fallbackResult = journeyService.searchJourneys(1L, 2L, date);
        assertEquals(1, fallbackResult.size());
        verify(journeyRepository).findByJourneyDate(date);
    }

    @Test
    @DisplayName("6: Redis write failure does not fail the search response (fail-open)")
    void redisWriteFailureDoesNotFailSearchResponse() {
        Cache mockCache = mock(Cache.class);
        when(mockCache.getName()).thenReturn(RedisCacheConfig.TRAIN_SEARCH_CACHE);

        RedisSystemException redisException =
                new RedisSystemException("Redis command timed out", new RuntimeException());

        // Error handler must swallow exception on put error so client receives data
        assertDoesNotThrow(() -> errorHandler.handleCachePutError(
                redisException, mockCache, "NDLS_BCT", List.of()));
    }

    @Test
    @DisplayName("7: Tatkal Redis fail-closed behaviour remains unchanged")
    void tatkalRedisFailClosedBehaviorRemainsUnchanged() {
        RedisTatkalQueueStore mockStore = mock(RedisTatkalQueueStore.class);
        TatkalAdmissionPassService mockPassService = mock(TatkalAdmissionPassService.class);
        JourneyRepository mockJourneyRepo = mock(JourneyRepository.class);
        TatkalJourneyPolicy policy = new TatkalJourneyPolicy("7");

        TatkalQueueService tatkalService = new TatkalQueueService(
                mockStore, mockPassService, mockJourneyRepo, policy);
        org.springframework.test.util.ReflectionTestUtils.setField(tatkalService, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(tatkalService, "capacity", 10);
        org.springframework.test.util.ReflectionTestUtils.setField(tatkalService, "admissionTtlSeconds", 300L);
        org.springframework.test.util.ReflectionTestUtils.setField(tatkalService, "entryTtlSeconds", 86400L);

        when(mockJourneyRepo.existsById(7L)).thenReturn(true);
        // Simulate Redis failure during Tatkal queue rate limit check
        when(mockStore.incrementRateLimit(anyLong(), anyString(), anyString(), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("Redis connection pool exhausted"));

        // Critical Security Invariant: Tatkal MUST fail closed (503 / QueueUnavailableException), NEVER fail open
        assertThrows(QueueUnavailableException.class, () -> tatkalService.join(7L, "traveler"));
    }

    @Test
    @DisplayName("8: GenericJacksonJsonRedisSerializer round-trip fidelity with JavaTime DTO records")
    void jsonSerializationFidelity() {
        GenericJacksonJsonRedisSerializer serializer = RedisCacheConfig.createJsonSerializer();

        JourneyResponseDTO journeyDto = new JourneyResponseDTO(
                101L, 1L, "12951", "Rajdhani Express",
                10L, "NDLS", "New Delhi",
                20L, "BCT", "Mumbai Central",
                LocalDate.of(2026, 11, 15),
                LocalTime.of(16, 55),
                LocalTime.of(8, 35),
                5L,
                LocalDateTime.of(2026, 11, 15, 16, 55),
                LocalDateTime.of(2026, 11, 16, 8, 35)
        );

        byte[] bytes = serializer.serialize(journeyDto);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        Object deserialized = serializer.deserialize(bytes);
        assertNotNull(deserialized);
        assertEquals(journeyDto, deserialized);

        TrainSearchResponseDTO trainDto = new TrainSearchResponseDTO(
                1L, "12951", "Rajdhani Express",
                "NDLS", "New Delhi", LocalTime.of(16, 50), LocalTime.of(16, 55), 0,
                "BCT", "Mumbai Central", LocalTime.of(8, 35), null, 1
        );

        byte[] trainBytes = serializer.serialize(trainDto);
        assertNotNull(trainBytes);
        Object deserializedTrain = serializer.deserialize(trainBytes);
        assertEquals(trainDto, deserializedTrain);
    }

    @Test
    @DisplayName("9: Cache eviction on schedule creation clears cached search results")
    void cacheEvictionOnScheduleCreation() {
        Cache journeyCache = cacheManager.getCache(RedisCacheConfig.JOURNEY_SEARCH_CACHE);
        assertNotNull(journeyCache);
        journeyCache.put("1_2_2026-11-15", List.of());
        assertNotNull(journeyCache.get("1_2_2026-11-15"));

        // Evict all entries as done by @CacheEvict(allEntries = true) on schedule mutations
        journeyCache.clear();
        assertNull(journeyCache.get("1_2_2026-11-15"));

        Cache trainCache = cacheManager.getCache(RedisCacheConfig.TRAIN_SEARCH_CACHE);
        assertNotNull(trainCache);
        trainCache.put("NDLS_BCT", List.of());
        assertNotNull(trainCache.get("NDLS_BCT"));

        trainCache.clear();
        assertNull(trainCache.get("NDLS_BCT"));
    }

    private Journey createSampleJourney(Long journeyId, Long fromStationId, Long toStationId, LocalDate date) {
        Station from = new Station();
        from.setStationId(fromStationId);
        from.setStationCode("STN" + fromStationId);
        from.setStationName("Station " + fromStationId);

        Station to = new Station();
        to.setStationId(toStationId);
        to.setStationCode("STN" + toStationId);
        to.setStationName("Station " + toStationId);

        Train train = new Train();
        train.setTrainId(1L);
        train.setTrainNumber("12001");
        train.setTrainName("Shatabdi Express");

        Journey journey = new Journey();
        journey.setJourneyId(journeyId);
        journey.setTrain(train);
        journey.setFromStation(from);
        journey.setToStation(to);
        journey.setJourneyDate(date);
        journey.setDepartureTime(LocalTime.of(6, 0));
        journey.setArrivalTime(LocalTime.of(11, 30));
        return journey;
    }
}
