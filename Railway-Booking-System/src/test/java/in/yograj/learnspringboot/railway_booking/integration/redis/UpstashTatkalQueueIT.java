package in.yograj.learnspringboot.railway_booking.integration.redis;

import in.yograj.learnspringboot.railway_booking.dto.TatkalQueueResponse;
import in.yograj.learnspringboot.railway_booking.exception.InvalidAdmissionPassException;
import in.yograj.learnspringboot.railway_booking.exception.QueueRateLimitException;
import in.yograj.learnspringboot.railway_booking.exception.QueueUnavailableException;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.*;
import in.yograj.learnspringboot.railway_booking.service.RedisTatkalQueueStore;
import in.yograj.learnspringboot.railway_booking.service.TatkalAdmissionPassService;
import in.yograj.learnspringboot.railway_booking.service.TatkalJourneyPolicy;
import in.yograj.learnspringboot.railway_booking.service.TatkalQueueEntry;
import in.yograj.learnspringboot.railway_booking.service.TatkalQueueService;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3: Real Upstash Redis Tatkal Queue Integration Verification.
 *
 * Verifies that Railora's Redis-backed Tatkal FIFO Queue, Lua scripts, admission pass signing,
 * rate limiting, and fail-closed error handling operate correctly with the live Upstash Redis instance (over TLS).
 *
 * Verification Scope:
 * 1. Queue JOIN populates expected Redis entries (user pointer, entry hash, sequence) and prevents duplicates.
 * 2. Multiple users receive strict FIFO positions (waiting sorted set with increasing sequence scores).
 * 3. ADMIT promotion selects users in correct FIFO order, allocates capacity, and assigns admission leases.
 * 4. LEAVE removes the target user's entries cleanly, advances subsequent waiters, and allows rejoining at the tail.
 * 5. Cryptographic admission pass lifecycle: HMAC-SHA binding to user, journey, entry ID, and lease expiry.
 * 6. Admission pass rejection on invalid user, wrong journey, tampered signatures, or expired lease.
 * 7. Two-phase hold transitions: CLAIM_HOLD (to HOLDING) and COMPLETE_HOLD (to HELD) in Redis.
 * 8. Independent rate limiting: JOIN limit (10/min) and POLL/STATUS limit (120/min) via Redis PEXPIRE Lua script.
 * 9. Production Lua scripts: JOIN, ADMIT, RATE_LIMIT, LEAVE, CLAIM_HOLD, UPDATE_STATE execute cleanly on Upstash.
 * 10. Strict fail-closed semantics: Redis connection/command failures throw QueueUnavailableException (HTTP 503).
 *
 * Activation:
 * This test is strictly opt-in and skipped during regular build/test runs.
 * To activate for live verification:
 *   set RUN_UPSTASH_SMOKE_TEST=true
 *   or pass: -Dupstash.smoke.enabled=true
 *
 * Safety Boundaries:
 * - Uses in-memory H2 database; zero interaction with PostgreSQL "railway_booking" database.
 * - Never logs or displays REDIS_PASSWORD or secret tokens.
 * - Tracks and deletes only the exact test keys created during execution; NO FLUSHDB / FLUSHALL.
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:upstash-tatkal-queue-it;DB_CLOSE_DELAY=-1",
        "DB_USERNAME=sa",
        "DB_PASSWORD=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:upstash-tatkal-queue-it;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "railway.outbox.kafka.enabled=false",
        "railway.seat-hold-reaper.enabled=false",
        "railway.data-seed.admin.enabled=false",
        "railway.data-seed.journey.enabled=false",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length",
        "tatkal.queue.enabled=true",
        "tatkal.queue.journey-ids=99901,99902,99903,99904,99905,99906,99907",
        "tatkal.queue.capacity=1",
        "tatkal.queue.admission-ttl-seconds=300",
        "tatkal.queue.entry-ttl-seconds=86400",
        "tatkal.queue.admission-secret=test-tatkal-admission-secret-key-at-least-32-bytes-long"
})
@EnabledIf("isOptedIn")
class UpstashTatkalQueueIT {

    private static final Logger log = LoggerFactory.getLogger(UpstashTatkalQueueIT.class);

    // Track all Redis keys created across test runs for guaranteed isolated cleanup
    private final Set<String> trackedKeys = ConcurrentHashMap.newKeySet();

    @Autowired
    private TatkalQueueService tatkalQueueService;

    @Autowired
    private RedisTatkalQueueStore store;

    @Autowired
    private TatkalAdmissionPassService passService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private JourneyRepository journeyRepository;

    @MockitoBean
    private TrainRepository trainRepository;

    @MockitoBean
    private CoachRepository coachRepository;

    @MockitoBean
    private SeatRepository seatRepository;

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
        when(journeyRepository.existsById(anyLong())).thenReturn(true);
    }

    @AfterEach
    void cleanupTrackedRedisKeys() {
        for (String key : trackedKeys) {
            try {
                Boolean deleted = stringRedisTemplate.delete(key);
                log.info("[UPSTASH TATKAL CLEANUP] Deleted test key '{}': {}", key, deleted);
            } catch (Exception ex) {
                log.warn("[UPSTASH TATKAL CLEANUP ERROR] Could not delete key '{}': {}", key, ex.getMessage());
            }
        }
        trackedKeys.clear();
    }

    private void trackJourneyKeys(long journeyId, List<String> users, List<String> entryIds) {
        String base = "tatkal:{" + journeyId + "}:";
        trackedKeys.add(base + "waiting");
        trackedKeys.add(base + "sequence");
        trackedKeys.add(base + "admitted");
        trackedKeys.add(base + "admission-sequence");
        for (String user : users) {
            trackedKeys.add(base + "user:" + user);
            trackedKeys.add(base + "rate:join:" + user);
            trackedKeys.add(base + "rate:status:" + user);
            trackedKeys.add(base + "rate:leave:" + user);
        }
        for (String entryId : entryIds) {
            trackedKeys.add(base + "entry:" + entryId);
        }
    }

    @Test
    @DisplayName("1. Queue JOIN: creates expected Redis entries in Upstash, sets sequence and prevents duplicate entries")
    void verifyQueueJoinCreatesExpectedRedisEntriesAndPreventsDuplicates() {
        long journeyId = 99901L;
        String username = "tatkal_user_join_1";
        trackJourneyKeys(journeyId, List.of(username), List.of());

        // Call 1: First join creates the queue entry in Upstash
        TatkalQueueResponse response1 = tatkalQueueService.join(journeyId, username);
        assertNotNull(response1);
        String entryId1 = response1.queueEntryId();
        assertNotNull(entryId1, "Entry ID must be generated by server");
        trackedKeys.add("tatkal:{" + journeyId + "}:entry:" + entryId1);
        assertEquals(journeyId, response1.journeyId());
        // With capacity=1, first user is immediately admitted
        assertEquals("ADMITTED", response1.status());
        assertNotNull(response1.admissionPass(), "Admitted user must receive signed admission pass");

        // Verify Redis structures directly in Upstash
        String userKey = "tatkal:{" + journeyId + "}:user:" + username;
        String entryKey = "tatkal:{" + journeyId + "}:entry:" + entryId1;

        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(stringRedisTemplate.hasKey(userKey)));

        assertEquals(entryId1, stringRedisTemplate.opsForValue().get(userKey),
                "User pointer key must point to the created entryId");

        Map<Object, Object> entryFields = stringRedisTemplate.opsForHash().entries(entryKey);
        assertFalse(entryFields.isEmpty(), "Entry hash must exist in Upstash Redis");
        assertEquals(entryId1, entryFields.get("entryId"));
        assertEquals(username, entryFields.get("username"));
        assertEquals(Long.toString(journeyId), entryFields.get("journeyId"));
        assertEquals("ADMITTED", entryFields.get("state"));
        assertNotNull(entryFields.get("sequence"));

        // Call 2: Duplicate join by the same user must return existing entryId without incrementing sequence
        TatkalQueueResponse response2 = tatkalQueueService.join(journeyId, username);
        assertNotNull(response2);
        assertEquals(entryId1, response2.queueEntryId(), "Repeated join by same user must return the existing entryId");
        log.info("[UPSTASH TATKAL SUCCESS] JOIN created expected entries and idempotently returned existing entry");
    }

    @Test
    @DisplayName("2. FIFO Ordering: Multiple waiting users receive strictly increasing sequence numbers and consecutive positions")
    void verifyMultipleUsersReceiveFifoQueuePositionsAndOrder() {
        long journeyId = 99902L;
        String userA = "fifo_user_a";
        String userB = "fifo_user_b";
        String userC = "fifo_user_c";
        String userD = "fifo_user_d";
        trackJourneyKeys(journeyId, List.of(userA, userB, userC, userD), List.of());

        // User A joins: fills capacity=1 and becomes ADMITTED
        TatkalQueueResponse respA = tatkalQueueService.join(journeyId, userA);
        trackedKeys.add("tatkal:{" + journeyId + "}:entry:" + respA.queueEntryId());
        assertEquals("ADMITTED", respA.status());
        assertNull(respA.position(), "Admitted user has no waiting position");

        // User B joins: enters WAITING queue at position 1
        TatkalQueueResponse respB = tatkalQueueService.join(journeyId, userB);
        trackedKeys.add("tatkal:{" + journeyId + "}:entry:" + respB.queueEntryId());
        assertEquals("WAITING", respB.status());
        assertEquals(1L, respB.position(), "First waiter must receive position 1");

        // User C joins: enters WAITING queue at position 2
        TatkalQueueResponse respC = tatkalQueueService.join(journeyId, userC);
        trackedKeys.add("tatkal:{" + journeyId + "}:entry:" + respC.queueEntryId());
        assertEquals("WAITING", respC.status());
        assertEquals(2L, respC.position(), "Second waiter must receive position 2");

        // User D joins: enters WAITING queue at position 3
        TatkalQueueResponse respD = tatkalQueueService.join(journeyId, userD);
        trackedKeys.add("tatkal:{" + journeyId + "}:entry:" + respD.queueEntryId());
        assertEquals("WAITING", respD.status());
        assertEquals(3L, respD.position(), "Third waiter must receive position 3");

        // Verify status endpoint reflects accurate live positions from Upstash zset rank
        assertEquals(1L, tatkalQueueService.status(journeyId, userB).position());
        assertEquals(2L, tatkalQueueService.status(journeyId, userC).position());
        assertEquals(3L, tatkalQueueService.status(journeyId, userD).position());

        // Verify Upstash sorted set scores match FIFO order
        String waitingZset = "tatkal:{" + journeyId + "}:waiting";
        Double scoreB = stringRedisTemplate.opsForZSet().score(waitingZset, respB.queueEntryId());
        Double scoreC = stringRedisTemplate.opsForZSet().score(waitingZset, respC.queueEntryId());
        Double scoreD = stringRedisTemplate.opsForZSet().score(waitingZset, respD.queueEntryId());

        assertNotNull(scoreB);
        assertNotNull(scoreC);
        assertNotNull(scoreD);
        assertTrue(scoreB < scoreC && scoreC < scoreD, "Scores in waiting zset must be strictly monotonic (FIFO)");
        log.info("[UPSTASH TATKAL SUCCESS] FIFO queue positions verified across 4 distinct users");
    }

    @Test
    @DisplayName("3. ADMIT Promotion: Releasing capacity promotes next waiter in FIFO order and grants admission pass")
    void verifyAdmitPromotesUsersInFifoOrder() {
        long journeyId = 99903L;
        String user1 = "admit_user_1";
        String user2 = "admit_user_2";
        String user3 = "admit_user_3";
        trackJourneyKeys(journeyId, List.of(user1, user2, user3), List.of());

        TatkalQueueResponse resp1 = tatkalQueueService.join(journeyId, user1); // ADMITTED (capacity 1)
        TatkalQueueResponse resp2 = tatkalQueueService.join(journeyId, user2); // WAITING (pos 1)
        TatkalQueueResponse resp3 = tatkalQueueService.join(journeyId, user3); // WAITING (pos 2)
        trackedKeys.add("tatkal:{" + journeyId + "}:entry:" + resp1.queueEntryId());
        trackedKeys.add("tatkal:{" + journeyId + "}:entry:" + resp2.queueEntryId());
        trackedKeys.add("tatkal:{" + journeyId + "}:entry:" + resp3.queueEntryId());

        assertEquals("ADMITTED", resp1.status());
        assertEquals("WAITING", resp2.status());
        assertEquals(1L, resp2.position());
        assertEquals("WAITING", resp3.status());
        assertEquals(2L, resp3.position());

        // User 1 leaves, releasing capacity in Upstash Redis
        tatkalQueueService.leave(journeyId, user1);

        // Verify User 2 is automatically promoted to ADMITTED upon next promotion cycle
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    TatkalQueueResponse status2 = tatkalQueueService.status(journeyId, user2);
                    return "ADMITTED".equals(status2.status());
                });

        TatkalQueueResponse promoted2 = tatkalQueueService.status(journeyId, user2);
        assertEquals("ADMITTED", promoted2.status());
        assertNull(promoted2.position());
        assertNotNull(promoted2.admissionPass(), "Promoted user must receive a valid admission pass");
        assertNotNull(promoted2.admittedUntil());

        // User 3 advances from position 2 to position 1
        TatkalQueueResponse status3 = tatkalQueueService.status(journeyId, user3);
        assertEquals("WAITING", status3.status());
        assertEquals(1L, status3.position(), "User 3 must advance to position 1");

        // Verify Upstash admitted zset contains User 2
        String admittedZset = "tatkal:{" + journeyId + "}:admitted";
        Double admittedScore = stringRedisTemplate.opsForZSet().score(admittedZset, resp2.queueEntryId());
        assertNotNull(admittedScore, "Promoted user must be present in Upstash admitted sorted set");
        log.info("[UPSTASH TATKAL SUCCESS] ADMIT promotion promoted earliest waiter and issued admission pass");
    }

    @Test
    @DisplayName("4. LEAVE & Rejoin: Leaving removes user from Redis, advances queue, and rejoining places user at the tail")
    void verifyLeaveRemovesOnlyTargetUserAndRejoiningPlacesAtTail() {
        long journeyId = 99904L;
        String userA = "leave_user_a";
        String userB = "leave_user_b";
        String userC = "leave_user_c";
        trackJourneyKeys(journeyId, List.of(userA, userB, userC), List.of());

        TatkalQueueResponse respA = tatkalQueueService.join(journeyId, userA); // ADMITTED
        TatkalQueueResponse respB = tatkalQueueService.join(journeyId, userB); // WAITING pos 1
        TatkalQueueResponse respC = tatkalQueueService.join(journeyId, userC); // WAITING pos 2
        trackedKeys.add("tatkal:{" + journeyId + "}:entry:" + respA.queueEntryId());
        trackedKeys.add("tatkal:{" + journeyId + "}:entry:" + respB.queueEntryId());
        trackedKeys.add("tatkal:{" + journeyId + "}:entry:" + respC.queueEntryId());

        assertEquals(1L, respB.position());
        assertEquals(2L, respC.position());

        // User B (waiting at pos 1) leaves the queue
        tatkalQueueService.leave(journeyId, userB);

        // Verify User B is removed from Redis
        String userBPointerKey = "tatkal:{" + journeyId + "}:user:" + userB;
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(userBPointerKey)),
                "User pointer key must be deleted in Upstash upon leave");

        // User C advances from position 2 to position 1
        TatkalQueueResponse statusC = tatkalQueueService.status(journeyId, userC);
        assertEquals(1L, statusC.position(), "User C must advance to position 1 after User B leaves");

        // Status check for User B must throw IllegalArgumentException (no active queue entry)
        assertThrows(IllegalArgumentException.class, () -> tatkalQueueService.status(journeyId, userB));

        // User B rejoins: must receive a NEW entry ID and be placed at the TAIL (position 2, behind User C)
        TatkalQueueResponse rejoinB = tatkalQueueService.join(journeyId, userB);
        assertNotEquals(respB.queueEntryId(), rejoinB.queueEntryId(), "Rejoining user must receive a fresh entryId");
        trackedKeys.add("tatkal:{" + journeyId + "}:entry:" + rejoinB.queueEntryId());
        assertEquals("WAITING", rejoinB.status());
        assertEquals(2L, rejoinB.position(), "Rejoining user must be placed at the tail of the queue");

        log.info("[UPSTASH TATKAL SUCCESS] LEAVE and rejoining verified against live Upstash sorted set");
    }

    @Test
    @DisplayName("5. Admission Pass Lifecycle: Valid pass verifies claims; tampered, expired, or mismatched passes are rejected")
    void verifyAdmissionPassLifecycleAndValidation() {
        long journeyId = 99905L;
        String username = "pass_user_1";
        trackJourneyKeys(journeyId, List.of(username), List.of());

        TatkalQueueResponse resp = tatkalQueueService.join(journeyId, username);
        trackedKeys.add("tatkal:{" + journeyId + "}:entry:" + resp.queueEntryId());
        assertEquals("ADMITTED", resp.status());
        String token = resp.admissionPass();
        assertNotNull(token);

        // 1. Valid token verification matches claims
        TatkalAdmissionPassService.AdmissionClaims claims = passService.verify(token, username, journeyId, false);
        assertEquals(resp.queueEntryId(), claims.queueEntryId());
        assertNotNull(claims.admissionId());
        assertTrue(claims.expiresAt().isAfter(Instant.now()));

        // 2. Mismatched username rejection
        assertThrows(InvalidAdmissionPassException.class,
                () -> passService.verify(token, "impostor_user", journeyId, false),
                "Pass must be rejected for mismatched username");

        // 3. Mismatched journey rejection
        assertThrows(InvalidAdmissionPassException.class,
                () -> passService.verify(token, username, 99999L, false),
                "Pass must be rejected for mismatched journey ID");

        // 4. Tampered token rejection
        int signatureIndex = token.lastIndexOf('.') + 1;
        char replacementChar = token.charAt(signatureIndex) == 'a' ? 'b' : 'a';
        String tamperedToken = token.substring(0, signatureIndex) + replacementChar + token.substring(signatureIndex + 1);
        assertThrows(InvalidAdmissionPassException.class,
                () -> passService.verify(tamperedToken, username, journeyId, false),
                "Tampered JWT signature must be rejected");

        // 5. Expired token rejection
        TatkalQueueEntry expiredEntry = new TatkalQueueEntry(
                "expired-entry", journeyId, username, "ADMITTED",
                Instant.now().minusSeconds(400), 1, "admission-old",
                Instant.now().minusSeconds(400), Instant.now().minusSeconds(10), null);
        String expiredToken = passService.issue(expiredEntry);
        assertThrows(InvalidAdmissionPassException.class,
                () -> passService.verify(expiredToken, username, journeyId, false),
                "Expired admission pass must be rejected when allowExpired=false");

        // Proving existing hold with allowExpired=true succeeds
        TatkalAdmissionPassService.AdmissionClaims existingHoldClaims =
                passService.verifyForExistingHold(expiredToken, username, journeyId);
        assertEquals("expired-entry", existingHoldClaims.queueEntryId());

        log.info("[UPSTASH TATKAL SUCCESS] Cryptographic admission pass lifecycle and boundary rejections verified");
    }

    @Test
    @DisplayName("6. Hold Lifecycle: claimSeatHold transitions to HOLDING in Upstash, completeSeatHold transitions to HELD")
    void verifySeatHoldClaimAndCompleteTransitionsStateInUpstash() {
        long journeyId = 99906L;
        String username = "hold_user_1";
        long seatId = 401L;
        trackJourneyKeys(journeyId, List.of(username), List.of());

        TatkalQueueResponse resp = tatkalQueueService.join(journeyId, username);
        String entryId = resp.queueEntryId();
        trackedKeys.add("tatkal:{" + journeyId + "}:entry:" + entryId);
        String pass = resp.admissionPass();

        // 1. Claim seat hold: transitions from ADMITTED to HOLDING in Upstash Redis
        TatkalAdmissionPassService.AdmissionClaims claims =
                tatkalQueueService.claimSeatHold(journeyId, username, pass, seatId);
        assertEquals(entryId, claims.queueEntryId());

        String entryKey = "tatkal:{" + journeyId + "}:entry:" + entryId;
        assertEquals("HOLDING", stringRedisTemplate.opsForHash().get(entryKey, "state"));
        assertEquals(Long.toString(seatId), stringRedisTemplate.opsForHash().get(entryKey, "heldSeatId"));

        // Leaving while in HOLDING state is prohibited
        assertThrows(IllegalStateException.class, () -> tatkalQueueService.leave(journeyId, username),
                "Leaving while in HOLDING state must be rejected");

        // 2. Complete seat hold: transitions from HOLDING to HELD in Upstash Redis
        LocalDateTime holdExpiresAt = LocalDateTime.now().plusMinutes(10);
        tatkalQueueService.completeSeatHold(journeyId, entryId, username, holdExpiresAt);

        assertEquals("HELD", stringRedisTemplate.opsForHash().get(entryKey, "state"));
        assertNotNull(stringRedisTemplate.opsForHash().get(entryKey, "heldUntil"));

        // Leaving while in HELD state is also prohibited
        assertThrows(IllegalStateException.class, () -> tatkalQueueService.leave(journeyId, username),
                "Leaving while in HELD state must be rejected");

        log.info("[UPSTASH TATKAL SUCCESS] Two-phase hold state transitions (HOLDING -> HELD) verified in Upstash");
    }

    @Test
    @DisplayName("7. Rate Limiting: JOIN limit (10/min) and STATUS limit (120/min) are enforced independently on Upstash")
    void verifyJoinAndPollRateLimitsAreEnforcedIndependently() {
        long journeyId = 99907L;
        String joinUser = "rate_user_join";
        String pollUser = "rate_user_poll";
        trackJourneyKeys(journeyId, List.of(joinUser, pollUser), List.of());

        // 1. Verify JOIN rate limiting (limit: 10 per 60,000ms window)
        for (int i = 1; i <= 10; i++) {
            long count = store.incrementRateLimit(journeyId, joinUser, "join", 60_000L);
            assertEquals(i, count, "Rate limit counter must increment by 1 for each join attempt");
        }
        // 11th request exceeds limit
        long count11 = store.incrementRateLimit(journeyId, joinUser, "join", 60_000L);
        assertTrue(count11 > 10, "11th join request must exceed threshold");

        // Calling service join must throw QueueRateLimitException
        assertThrows(QueueRateLimitException.class, () -> tatkalQueueService.join(journeyId, joinUser));

        // Verify rate key has an active TTL in Upstash
        String rateJoinKey = "tatkal:{" + journeyId + "}:rate:join:" + joinUser;
        Long joinTtl = stringRedisTemplate.getExpire(rateJoinKey, TimeUnit.SECONDS);
        assertNotNull(joinTtl);
        assertTrue(joinTtl > 0 && joinTtl <= 60, "Rate limit key must expire within 60s window");

        // 2. Verify STATUS/POLL rate limit is independent from JOIN rate limit
        // pollUser status rate limit is 120 per 60,000ms window
        for (int i = 1; i <= 120; i++) {
            long count = store.incrementRateLimit(journeyId, pollUser, "status", 60_000L);
            assertEquals(i, count);
        }
        long count121 = store.incrementRateLimit(journeyId, pollUser, "status", 60_000L);
        assertTrue(count121 > 120, "121st status poll must exceed limit");

        // pollUser's join counter is completely untouched and independent
        long pollUserJoinCount = store.incrementRateLimit(journeyId, pollUser, "join", 60_000L);
        assertEquals(1, pollUserJoinCount, "Status rate limit must not pollute join rate limit");

        log.info("[UPSTASH TATKAL SUCCESS] Independent JOIN and STATUS rate limits verified on Upstash via PEXPIRE Lua script");
    }

    @Test
    @DisplayName("8. Fail-Closed Resilience: Redis connection/command failures throw QueueUnavailableException (HTTP 503)")
    void verifyFailClosedSemanticsOnRedisFailure() {
        RedisTatkalQueueStore mockStore = mock(RedisTatkalQueueStore.class);
        TatkalAdmissionPassService mockPassService = mock(TatkalAdmissionPassService.class);
        JourneyRepository mockJourneyRepo = mock(JourneyRepository.class);
        TatkalJourneyPolicy policy = new TatkalJourneyPolicy("99901");

        TatkalQueueService resilientService = new TatkalQueueService(
                mockStore, mockPassService, mockJourneyRepo, policy);
        org.springframework.test.util.ReflectionTestUtils.setField(resilientService, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(resilientService, "capacity", 1);
        org.springframework.test.util.ReflectionTestUtils.setField(resilientService, "admissionTtlSeconds", 300L);
        org.springframework.test.util.ReflectionTestUtils.setField(resilientService, "entryTtlSeconds", 86400L);

        when(mockJourneyRepo.existsById(99901L)).thenReturn(true);
        when(mockStore.incrementRateLimit(anyLong(), anyString(), anyString(), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("Upstash Redis connection timed out"));

        // Critical safety invariant: Tatkal operations must fail CLOSED (503 Service Unavailable), NEVER fail open
        assertThrows(QueueUnavailableException.class, () -> resilientService.join(99901L, "victim_user"),
                "join must fail closed on Redis failure");

        assertThrows(QueueUnavailableException.class, () -> resilientService.status(99901L, "victim_user"),
                "status must fail closed on Redis failure");

        assertThrows(QueueUnavailableException.class, () -> resilientService.leave(99901L, "victim_user"),
                "leave must fail closed on Redis failure");

        log.info("[UPSTASH TATKAL SUCCESS] Fail-closed semantics strictly confirmed for all queue operations");
    }
}
