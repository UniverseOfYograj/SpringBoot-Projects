package in.yograj.learnspringboot.railway_booking.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Resilient Redis cache configuration for high-frequency read paths:
 * - Journey search (route segments, schedules by date)
 * - Train search (station-to-station train discovery)
 * - Train and Coach static topology/layout (immutable train-coach-seat arrangement)
 *
 * Fault-Tolerance Policy:
 * Read caching fails open via {@link ResilientCacheErrorHandler}. If Redis is offline,
 * connection times out, or cache operations fail, calls transparently fall back
 * to PostgreSQL without throwing errors or disrupting user search.
 *
 * Note: Dynamic seat availability, holds, and bookings are strictly NEVER cached in Redis.
 * Tatkal queue operations do NOT use Spring Cache and remain strictly fail-closed.
 */
@Configuration
@EnableCaching
public class RedisCacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheConfig.class);

    public static final String JOURNEY_SEARCH_CACHE = "journeySearch";
    public static final String TRAIN_SEARCH_CACHE = "trainSearch";
    public static final String TRAIN_TOPOLOGY_CACHE = "trainTopology";

    @Bean
    @Primary
    public CacheManager cacheManager(
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
            @Value("${railway.cache.enabled:true}") boolean cacheEnabled,
            @Value("${railway.cache.journey-search.ttl-minutes:10}") int journeySearchTtlMinutes,
            @Value("${railway.cache.train-search.ttl-minutes:30}") int trainSearchTtlMinutes,
            @Value("${railway.cache.topology.ttl-hours:24}") int topologyTtlHours) {

        RedisConnectionFactory connectionFactory = connectionFactoryProvider.getIfAvailable();
        if (!cacheEnabled || connectionFactory == null) {
            log.info("Redis search cache is disabled or connection factory unavailable; using in-memory ConcurrentMapCacheManager");
            return new ConcurrentMapCacheManager(JOURNEY_SEARCH_CACHE, TRAIN_SEARCH_CACHE, TRAIN_TOPOLOGY_CACHE);
        }

        GenericJacksonJsonRedisSerializer jsonSerializer = createJsonSerializer();

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer));

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        cacheConfigurations.put(JOURNEY_SEARCH_CACHE,
                defaultConfig.entryTtl(Duration.ofMinutes(Math.max(1, journeySearchTtlMinutes))));
        cacheConfigurations.put(TRAIN_SEARCH_CACHE,
                defaultConfig.entryTtl(Duration.ofMinutes(Math.max(1, trainSearchTtlMinutes))));
        cacheConfigurations.put(TRAIN_TOPOLOGY_CACHE,
                defaultConfig.entryTtl(Duration.ofHours(Math.max(1, topologyTtlHours))));

        log.info("Initialized RedisCacheManager with TTL: journeySearch={}m, trainSearch={}m, trainTopology={}h",
                journeySearchTtlMinutes, trainSearchTtlMinutes, topologyTtlHours);

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }

    public static GenericJacksonJsonRedisSerializer createJsonSerializer() {
        return GenericJacksonJsonRedisSerializer.builder()
                .enableSpringCacheNullValueSupport()
                .enableUnsafeDefaultTyping()
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new ResilientCacheErrorHandler();
    }

    /**
     * Fail-open cache error handler. Logs Redis exceptions as warnings and swallows
     * them so the request transparently falls back to the backing database query.
     */
    public static class ResilientCacheErrorHandler implements CacheErrorHandler {

        private static final Logger log = LoggerFactory.getLogger(ResilientCacheErrorHandler.class);

        @Override
        public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
            log.warn("Redis cache GET failed for cache='{}', key='{}'. Bypassing cache to query database: {}",
                    cache != null ? cache.getName() : "unknown", key, exception.getMessage());
        }

        @Override
        public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
            log.warn("Redis cache PUT failed for cache='{}', key='{}'. Returning database result normally: {}",
                    cache != null ? cache.getName() : "unknown", key, exception.getMessage());
        }

        @Override
        public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
            log.warn("Redis cache EVICT failed for cache='{}', key='{}'. Proceeding without failing operation: {}",
                    cache != null ? cache.getName() : "unknown", key, exception.getMessage());
        }

        @Override
        public void handleCacheClearError(RuntimeException exception, Cache cache) {
            log.warn("Redis cache CLEAR failed for cache='{}': {}",
                    cache != null ? cache.getName() : "unknown", exception.getMessage());
        }
    }
}
