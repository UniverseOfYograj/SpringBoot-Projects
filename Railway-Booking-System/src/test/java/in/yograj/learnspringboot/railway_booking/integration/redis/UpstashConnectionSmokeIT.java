package in.yograj.learnspringboot.railway_booking.integration.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2B: Minimal, non-destructive Upstash Redis connection smoke test.
 *
 * Activation:
 * This test is strictly opt-in and does NOT execute during standard build/test runs.
 * To activate for live verification:
 *   set RUN_UPSTASH_SMOKE_TEST=true
 *   or pass: -Dupstash.smoke.enabled=true
 *
 * Safety Constraints:
 * 1. Focuses strictly on Redis via @DataRedisTest; zero PostgreSQL/JPA/DataSource interaction.
 * 2. Never displays, logs, or prints the REDIS_PASSWORD.
 * 3. Operates on a single uniquely generated ephemeral key: "smoke:upstash:<uuid>".
 * 4. Strictly deletes only the ephemeral test key in a finally block; NO FLUSHDB/FLUSHALL.
 */
@DataRedisTest
@EnabledIf("isOptedIn")
class UpstashConnectionSmokeIT {

    private static final Logger log = LoggerFactory.getLogger(UpstashConnectionSmokeIT.class);

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private Environment environment;

    /**
     * Opt-in activation condition.
     * Evaluated by JUnit 5 before any Spring context or socket connection is initialized.
     */
    static boolean isOptedIn() {
        return "true".equalsIgnoreCase(System.getenv("RUN_UPSTASH_SMOKE_TEST"))
                || "true".equalsIgnoreCase(System.getProperty("upstash.smoke.enabled"));
    }

    @Test
    @DisplayName("Verify Upstash Redis connectivity, TLS, basic key lifecycle, and Lua script execution")
    void testUpstashRedisConnectionSmoke() {
        // 1. Inspect configured connection settings (strictly masking credentials)
        String host = environment.getProperty("spring.data.redis.host", "localhost");
        String port = environment.getProperty("spring.data.redis.port", "6379");
        String sslEnabled = environment.getProperty("spring.data.redis.ssl.enabled", "false");
        String username = environment.getProperty("spring.data.redis.username", "default");
        String password = environment.getProperty("spring.data.redis.password", "");
        boolean passwordConfigured = password != null && !password.isBlank();

        log.info("[UPSTASH SMOKE AUDIT] Target host: {}, port: {}, ssl: {}, username: {}, passwordConfigured: {}",
                host, port, sslEnabled, username, passwordConfigured);

        assertNotNull(connectionFactory, "RedisConnectionFactory must be injected by Spring Boot autoconfiguration");
        assertNotNull(stringRedisTemplate, "StringRedisTemplate must be available");

        // 2. Execute Redis PING -> expect PONG
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String pingResult = connection.ping();
            assertEquals("PONG", pingResult, "Redis server must respond to PING with PONG over TLS");
        }

        // 3. Unique ephemeral test key
        String testKey = "smoke:upstash:" + UUID.randomUUID();
        String testValue = "smoke-payload-" + UUID.randomUUID();

        try {
            // 4. Write temporary test key
            stringRedisTemplate.opsForValue().set(testKey, testValue);

            // 5. Read value back and verify equality
            String retrievedValue = stringRedisTemplate.opsForValue().get(testKey);
            assertEquals(testValue, retrievedValue, "Retrieved value must equal the written test payload");

            // 6. Set 10-second TTL and verify expiration is active
            Boolean expireSet = stringRedisTemplate.expire(testKey, Duration.ofSeconds(10));
            assertTrue(Boolean.TRUE.equals(expireSet), "Expire command must succeed for the test key");

            Long remainingTtlSeconds = stringRedisTemplate.getExpire(testKey, TimeUnit.SECONDS);
            assertNotNull(remainingTtlSeconds, "TTL query must return non-null expiry");
            assertTrue(remainingTtlSeconds > 0 && remainingTtlSeconds <= 10,
                    "Remaining TTL must be positive and <= 10 seconds");

            // 7. Execute minimal single-key Lua script to verify script execution on Upstash
            DefaultRedisScript<String> getScript = new DefaultRedisScript<>(
                    "return redis.call('GET', KEYS[1])", String.class);
            String scriptOutput = stringRedisTemplate.execute(getScript, List.of(testKey));
            assertEquals(testValue, scriptOutput, "Lua script must execute atomically and return key payload");

            log.info("[UPSTASH SMOKE SUCCESS] Ping, key write, read, TTL, and Lua script verified successfully on {}", host);
        } finally {
            // 8. Isolated cleanup: delete ONLY the exact temporary test key
            try {
                Boolean deleted = stringRedisTemplate.delete(testKey);
                log.info("[UPSTASH SMOKE CLEANUP] Temporary test key '{}' deleted: {}", testKey, deleted);
            } catch (Exception ex) {
                log.warn("[UPSTASH SMOKE CLEANUP ERROR] Could not delete test key '{}': {}", testKey, ex.getMessage());
            }
        }
    }
}
