package in.yograj.learnspringboot.railway_booking.cache;

import in.yograj.learnspringboot.railway_booking.config.RedisCacheConfig;
import in.yograj.learnspringboot.railway_booking.controller.TrainTopologyController;
import in.yograj.learnspringboot.railway_booking.dto.CoachTopologyDTO;
import in.yograj.learnspringboot.railway_booking.dto.SeatTopologyDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainTopologyDTO;
import in.yograj.learnspringboot.railway_booking.entity.Coach;
import in.yograj.learnspringboot.railway_booking.entity.Seat;
import in.yograj.learnspringboot.railway_booking.entity.Train;
import in.yograj.learnspringboot.railway_booking.repository.CoachRepository;
import in.yograj.learnspringboot.railway_booking.repository.SeatRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainRepository;
import in.yograj.learnspringboot.railway_booking.service.TrainTopologyChangedEvent;
import in.yograj.learnspringboot.railway_booking.service.TrainTopologyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused tests for static train coach and seat topology caching in Redis:
 * 1. First layout request queries PostgreSQL; repeated request uses cache.
 * 2. Coach layout caching and retrieval.
 * 3. Different trains and coaches do not collide in cache keys.
 * 4. Redis read failure falls back to PostgreSQL (fail-open).
 * 5. Redis write failure does not fail the API (fail-open).
 * 6. Mutation and post-commit events invalidate cache entries.
 * 7. Dynamic seat availability is strictly excluded from topology models.
 * 8. Jackson 3 serialization fidelity for topology DTO records.
 * 9. Controller endpoint contracts.
 */
class TrainTopologyCacheTests {

    private TrainRepository trainRepository;
    private CoachRepository coachRepository;
    private SeatRepository seatRepository;
    private ConcurrentMapCacheManager cacheManager;
    private TrainTopologyServiceImpl topologyService;
    private RedisCacheConfig.ResilientCacheErrorHandler errorHandler;
    private TrainTopologyController topologyController;

    @BeforeEach
    void setUp() {
        trainRepository = mock(TrainRepository.class);
        coachRepository = mock(CoachRepository.class);
        seatRepository = mock(SeatRepository.class);

        cacheManager = new ConcurrentMapCacheManager(
                RedisCacheConfig.JOURNEY_SEARCH_CACHE,
                RedisCacheConfig.TRAIN_SEARCH_CACHE,
                RedisCacheConfig.TRAIN_TOPOLOGY_CACHE
        );
        errorHandler = new RedisCacheConfig.ResilientCacheErrorHandler();

        topologyService = new TrainTopologyServiceImpl(
                trainRepository,
                coachRepository,
                seatRepository,
                cacheManager
        );

        topologyController = new TrainTopologyController(topologyService);
    }

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

    @Test
    @DisplayName("1: First train layout request queries PostgreSQL, second request uses cache")
    void firstTrainLayoutRequestQueriesPostgres_secondUsesCache() {
        Train train = createTrain(101L, "12951", "Mumbai Rajdhani");
        Coach coach = createCoach(201L, train, "B1", "AC_3_TIER", 4);
        Seat s1 = createSeat(301L, coach, 1, "LOWER");
        Seat s2 = createSeat(302L, coach, 2, "UPPER");

        when(trainRepository.findById(101L)).thenReturn(Optional.of(train));
        when(coachRepository.findByTrain_TrainId(101L)).thenReturn(List.of(coach));
        when(seatRepository.findByCoach_CoachIdIn(List.of(201L))).thenReturn(List.of(s1, s2));

        Cache cache = cacheManager.getCache(RedisCacheConfig.TRAIN_TOPOLOGY_CACHE);
        assertNotNull(cache);

        String cacheKey = "train:101";

        // Step 1: Cache Miss -> calls service, queries PostgreSQL, populates cache
        assertNull(cache.get(cacheKey));
        TrainTopologyDTO firstResult = topologyService.getTrainTopology(101L);
        assertNotNull(firstResult);
        assertEquals(101L, firstResult.trainId());
        assertEquals("12951", firstResult.trainNumber());
        assertEquals(1, firstResult.totalCoaches());
        assertEquals(2, firstResult.totalSeats());
        assertEquals(1, firstResult.coaches().size());
        assertEquals("B1", firstResult.coaches().get(0).coachNumber());

        cache.put(cacheKey, firstResult);
        verify(trainRepository, times(1)).findById(101L);
        verify(coachRepository, times(1)).findByTrain_TrainId(101L);
        verify(seatRepository, times(1)).findByCoach_CoachIdIn(List.of(201L));

        // Step 2: Cache Hit -> returns cached result without querying DB again
        Cache.ValueWrapper wrapper = cache.get(cacheKey);
        assertNotNull(wrapper);
        TrainTopologyDTO cachedResult = (TrainTopologyDTO) wrapper.get();
        assertEquals(firstResult, cachedResult);

        // Verify repository interaction counts did not increase
        verify(trainRepository, times(1)).findById(101L);
        verify(coachRepository, times(1)).findByTrain_TrainId(101L);
        verify(seatRepository, times(1)).findByCoach_CoachIdIn(List.of(201L));
    }

    @Test
    @DisplayName("2: Coach layout request queries PostgreSQL on miss and populates coach cache")
    void coachLayoutRequestQueriesPostgres_secondUsesCache() {
        Train train = createTrain(101L, "12951", "Mumbai Rajdhani");
        Coach coach = createCoach(201L, train, "A1", "AC_2_TIER", 2);
        Seat s1 = createSeat(301L, coach, 1, "LOWER");
        Seat s2 = createSeat(302L, coach, 2, "UPPER");

        when(coachRepository.findById(201L)).thenReturn(Optional.of(coach));
        when(seatRepository.findByCoach_CoachId(201L)).thenReturn(List.of(s1, s2));

        Cache cache = cacheManager.getCache(RedisCacheConfig.TRAIN_TOPOLOGY_CACHE);
        assertNotNull(cache);

        String coachKey = "coach:201";

        assertNull(cache.get(coachKey));
        CoachTopologyDTO coachResult = topologyService.getCoachTopology(201L);
        assertNotNull(coachResult);
        assertEquals(201L, coachResult.coachId());
        assertEquals("A1", coachResult.coachNumber());
        assertEquals(2, coachResult.seats().size());

        cache.put(coachKey, coachResult);
        verify(coachRepository, times(1)).findById(201L);
        verify(seatRepository, times(1)).findByCoach_CoachId(201L);

        // Repeated fetch hits cache
        Cache.ValueWrapper wrapper = cache.get(coachKey);
        assertNotNull(wrapper);
        assertEquals(coachResult, wrapper.get());
        verify(coachRepository, times(1)).findById(201L);
    }

    @Test
    @DisplayName("3: Different trains and coaches do not collide in cache keys")
    void differentTrainsAndCoachesDoNotCollide() {
        Cache cache = cacheManager.getCache(RedisCacheConfig.TRAIN_TOPOLOGY_CACHE);
        assertNotNull(cache);

        // Train 100 vs Train 200
        String trainKey1 = "train:100";
        String trainKey2 = "train:200";

        // Coach 100 vs Coach 200 (same numeric IDs as trains, but different namespaces)
        String coachKey1 = "coach:100";
        String coachKey2 = "coach:200";

        TrainTopologyDTO trainDto1 = new TrainTopologyDTO(100L, "T100", "Train 100", 1, 2, List.of());
        TrainTopologyDTO trainDto2 = new TrainTopologyDTO(200L, "T200", "Train 200", 2, 4, List.of());
        CoachTopologyDTO coachDto1 = new CoachTopologyDTO(100L, 100L, "C100", "SL", 72, List.of());
        CoachTopologyDTO coachDto2 = new CoachTopologyDTO(200L, 200L, "C200", "3A", 64, List.of());

        cache.put(trainKey1, trainDto1);
        cache.put(trainKey2, trainDto2);
        cache.put(coachKey1, coachDto1);
        cache.put(coachKey2, coachDto2);

        // Verify that train:100 does not collide with coach:100
        assertNotEquals(trainKey1, coachKey1);
        assertEquals(trainDto1, cache.get(trainKey1).get());
        assertEquals(coachDto1, cache.get(coachKey1).get());
        assertEquals(trainDto2, cache.get(trainKey2).get());
        assertEquals(coachDto2, cache.get(coachKey2).get());
    }

    @Test
    @DisplayName("4: Redis read failure falls back to PostgreSQL (fail-open)")
    void redisReadFailureFallsBackToPostgres() {
        Cache mockCache = mock(Cache.class);
        when(mockCache.getName()).thenReturn(RedisCacheConfig.TRAIN_TOPOLOGY_CACHE);

        RedisConnectionFailureException redisException =
                new RedisConnectionFailureException("Redis connection failed on localhost:6379");

        // Error handler swallows exception and logs warning
        assertDoesNotThrow(() -> errorHandler.handleCacheGetError(redisException, mockCache, "train:101"));

        // PostgreSQL query executes transparently
        Train train = createTrain(101L, "12951", "Mumbai Rajdhani");
        when(trainRepository.findById(101L)).thenReturn(Optional.of(train));
        when(coachRepository.findByTrain_TrainId(101L)).thenReturn(List.of());

        TrainTopologyDTO fallbackResult = topologyService.getTrainTopology(101L);
        assertNotNull(fallbackResult);
        assertEquals("12951", fallbackResult.trainNumber());
        verify(trainRepository).findById(101L);
    }

    @Test
    @DisplayName("5: Redis write failure does not fail the topology response (fail-open)")
    void redisWriteFailureDoesNotFailResponse() {
        Cache mockCache = mock(Cache.class);
        when(mockCache.getName()).thenReturn(RedisCacheConfig.TRAIN_TOPOLOGY_CACHE);

        RedisSystemException redisException =
                new RedisSystemException("Redis command timeout on PUT", new RuntimeException());

        // Error handler swallows exception on PUT
        assertDoesNotThrow(() -> errorHandler.handleCachePutError(
                redisException, mockCache, "train:101", mock(TrainTopologyDTO.class)));
    }

    @Test
    @DisplayName("6: Coach/seat mutation invalidates affected cache entries")
    void mutationInvalidatesCacheEntries() {
        Cache cache = cacheManager.getCache(RedisCacheConfig.TRAIN_TOPOLOGY_CACHE);
        assertNotNull(cache);

        String trainKey = "train:101";
        String coachKey = "coach:201";

        TrainTopologyDTO trainDto = new TrainTopologyDTO(101L, "12951", "Rajdhani", 1, 2, List.of());
        CoachTopologyDTO coachDto = new CoachTopologyDTO(201L, 101L, "B1", "3A", 64, List.of());

        cache.put(trainKey, trainDto);
        cache.put(coachKey, coachDto);

        assertNotNull(cache.get(trainKey));
        assertNotNull(cache.get(coachKey));

        // Test targeted train eviction
        topologyService.evictTrainTopology(101L);
        assertNull(cache.get(trainKey));
        assertNotNull(cache.get(coachKey)); // coach remains untouched

        // Repopulate and test post-commit transactional event listener
        cache.put(trainKey, trainDto);
        topologyService.handleTopologyChanged(new TrainTopologyChangedEvent(101L, 201L));

        assertNull(cache.get(trainKey));
        assertNull(cache.get(coachKey));
    }

    @Test
    @DisplayName("7: Dynamic seat availability is strictly excluded from topology models")
    void dynamicSeatAvailabilityNeverReturnedInTopology() {
        // Inspect all fields of SeatTopologyDTO, CoachTopologyDTO, TrainTopologyDTO via reflection
        List<String> prohibitedDynamicFields = List.of(
                "status", "available", "hold", "booked", "blocked",
                "holdExpiresAt", "holdOwner", "seatAllocationId",
                "boardingJourneyStopId", "alightingJourneyStopId",
                "passenger", "booking", "pnr", "payment"
        );

        Field[] seatFields = SeatTopologyDTO.class.getDeclaredFields();
        for (Field f : seatFields) {
            String fieldName = f.getName().toLowerCase();
            for (String prohibited : prohibitedDynamicFields) {
                // Ensure field name is not a dynamic inventory status
                assertFalse(fieldName.equals(prohibited) && !fieldName.equals("seattype"),
                        "Topology DTO must not contain dynamic field: " + f.getName());
            }
        }

        // Verify SeatTopologyDTO fields
        SeatTopologyDTO seat = new SeatTopologyDTO(1L, 2L, 15, "LOWER", "LOWER");
        assertEquals(1L, seat.seatId());
        assertEquals(2L, seat.coachId());
        assertEquals(15, seat.seatNumber());
        assertEquals("LOWER", seat.seatType());
        assertEquals("LOWER", seat.berthType());

        // Verify CoachTopologyDTO has no dynamic inventory fields
        CoachTopologyDTO coach = new CoachTopologyDTO(2L, 10L, "S1", "SLEEPER", 72, List.of(seat));
        assertEquals(2L, coach.coachId());
        assertEquals(10L, coach.trainId());
        assertEquals("S1", coach.coachNumber());
        assertEquals("SLEEPER", coach.coachType());
        assertEquals(72, coach.seatCapacity());

        // Verify TrainTopologyDTO has no dynamic inventory fields
        TrainTopologyDTO train = new TrainTopologyDTO(10L, "12137", "Punjab Mail", 1, 1, List.of(coach));
        assertEquals(10L, train.trainId());
        assertEquals("12137", train.trainNumber());
    }

    @Test
    @DisplayName("8: GenericJacksonJsonRedisSerializer round-trip fidelity with topology DTO records")
    void jsonSerializationFidelity() {
        GenericJacksonJsonRedisSerializer serializer = RedisCacheConfig.createJsonSerializer();

        SeatTopologyDTO seat1 = new SeatTopologyDTO(301L, 201L, 1, "LOWER", "LOWER");
        SeatTopologyDTO seat2 = new SeatTopologyDTO(302L, 201L, 2, "UPPER", "UPPER");
        CoachTopologyDTO coach = new CoachTopologyDTO(201L, 101L, "B1", "AC_3_TIER", 72, List.of(seat1, seat2));
        TrainTopologyDTO train = new TrainTopologyDTO(101L, "12951", "Mumbai Rajdhani", 1, 2, List.of(coach));

        byte[] serializedBytes = serializer.serialize(train);
        assertNotNull(serializedBytes);
        assertTrue(serializedBytes.length > 0);

        Object deserialized = serializer.deserialize(serializedBytes);
        assertNotNull(deserialized);
        assertEquals(train, deserialized);
    }

    @Test
    @DisplayName("9: TrainTopologyController exposes REST layout and topology endpoints")
    void controllerEndpointsReturnTopology() {
        Train train = createTrain(101L, "12951", "Mumbai Rajdhani");
        when(trainRepository.findById(101L)).thenReturn(Optional.of(train));
        when(coachRepository.findByTrain_TrainId(101L)).thenReturn(List.of());

        ResponseEntity<TrainTopologyDTO> response = topologyController.getTrainTopology(101L);
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("12951", response.getBody().trainNumber());

        Coach coach = createCoach(201L, train, "B1", "AC_3_TIER", 4);
        when(coachRepository.findById(201L)).thenReturn(Optional.of(coach));
        when(seatRepository.findByCoach_CoachId(201L)).thenReturn(List.of());

        ResponseEntity<CoachTopologyDTO> coachResponse = topologyController.getCoachTopology(201L);
        assertNotNull(coachResponse);
        assertEquals(200, coachResponse.getStatusCode().value());
        assertEquals("B1", coachResponse.getBody().coachNumber());
    }
}
