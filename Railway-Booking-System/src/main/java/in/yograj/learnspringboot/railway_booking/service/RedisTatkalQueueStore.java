package in.yograj.learnspringboot.railway_booking.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class RedisTatkalQueueStore {
    private static final DefaultRedisScript<String> JOIN_SCRIPT = new DefaultRedisScript<>("""
            local existing = redis.call('GET', KEYS[3])
            if existing then
              local oldKey = ARGV[5] .. existing
              local oldState = redis.call('HGET', oldKey, 'state')
              local oldExpiry = tonumber(redis.call('HGET', oldKey, 'admittedUntil') or '0')
              if oldState == 'ADMITTED' and oldExpiry <= tonumber(ARGV[3]) then
                redis.call('HSET', oldKey, 'state', 'EXPIRED')
                redis.call('ZREM', KEYS[5], existing)
                oldState = 'EXPIRED'
              end
              local heldUntil = tonumber(redis.call('HGET', oldKey, 'heldUntil') or '0')
              if oldState == 'HELD' and heldUntil <= tonumber(ARGV[3]) then
                redis.call('HSET', oldKey, 'state', 'EXPIRED')
                oldState = 'EXPIRED'
              end
              if oldState == 'WAITING' or oldState == 'ADMITTED' or oldState == 'HOLDING' or oldState == 'HELD' then
                return existing
              end
            end
            local sequence = redis.call('INCR', KEYS[2])
            redis.call('ZADD', KEYS[1], sequence, ARGV[2])
            redis.call('HSET', KEYS[4],
              'entryId', ARGV[2], 'journeyId', ARGV[1], 'username', ARGV[4],
              'state', 'WAITING', 'joinedAt', ARGV[6], 'sequence', sequence)
            redis.call('EXPIRE', KEYS[4], ARGV[7])
            redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[7])
            return ARGV[2]
            """, String.class);

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);

    private static final DefaultRedisScript<Long> ADMIT_SCRIPT = new DefaultRedisScript<>("""
            local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', ARGV[2])
            for _, expiredId in ipairs(expired) do
              local expiredKey = ARGV[5] .. expiredId
              if redis.call('HGET', expiredKey, 'state') == 'ADMITTED' then
                redis.call('HSET', expiredKey, 'state', 'EXPIRED')
              end
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', ARGV[2])
            local capacity = tonumber(ARGV[1])
            local promoted = 0
            while redis.call('ZCARD', KEYS[2]) < capacity do
              local candidate = redis.call('ZRANGE', KEYS[1], 0, 0)
              if #candidate == 0 then break end
              local entryId = candidate[1]
              redis.call('ZREM', KEYS[1], entryId)
              local entryKey = ARGV[5] .. entryId
              if redis.call('HGET', entryKey, 'state') == 'WAITING' then
                local admissionId = ARGV[6] .. '-' .. redis.call('INCR', KEYS[3])
                local admittedAt = tonumber(ARGV[2])
                local admittedUntil = admittedAt + tonumber(ARGV[3]) * 1000
                redis.call('HSET', entryKey, 'state', 'ADMITTED',
                  'admissionId', admissionId, 'admittedAt', admittedAt, 'admittedUntil', admittedUntil)
                redis.call('ZADD', KEYS[2], admittedUntil, entryId)
                redis.call('EXPIRE', entryKey, ARGV[4])
                promoted = promoted + 1
              end
            end
            return promoted
            """, Long.class);

    private static final DefaultRedisScript<String> CLAIM_HOLD_SCRIPT = new DefaultRedisScript<>("""
            local state = redis.call('HGET', KEYS[1], 'state')
            local expiry = tonumber(redis.call('HGET', KEYS[1], 'admittedUntil') or '0')
            if state ~= 'ADMITTED' or expiry <= tonumber(ARGV[3]) then return 'REJECTED' end
            if redis.call('HGET', KEYS[1], 'username') ~= ARGV[1]
              or redis.call('HGET', KEYS[1], 'admissionId') ~= ARGV[2] then return 'REJECTED' end
            redis.call('HSET', KEYS[1], 'state', 'HOLDING', 'heldSeatId', ARGV[4])
            return 'CLAIMED'
            """, String.class);

    private static final DefaultRedisScript<Long> UPDATE_STATE_SCRIPT = new DefaultRedisScript<>("""
            local state = redis.call('HGET', KEYS[1], 'state')
            if state ~= ARGV[1] then return 0 end
            if ARGV[2] == 'HELD' then
              redis.call('HSET', KEYS[1], 'state', 'HELD', 'heldUntil', ARGV[5])
              redis.call('EXPIRE', KEYS[1], ARGV[6])
              redis.call('SET', KEYS[3], ARGV[4], 'EX', ARGV[6])
            else
              redis.call('HSET', KEYS[1], 'state', ARGV[2])
            end
            if ARGV[3] == 'release-capacity' then redis.call('ZREM', KEYS[2], ARGV[4]) end
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> LEAVE_SCRIPT = new DefaultRedisScript<>("""
            local state = redis.call('HGET', KEYS[1], 'state')
            if state == 'HOLDING' or state == 'HELD' then return -1 end
            if state ~= 'WAITING' and state ~= 'ADMITTED' then return 0 end
            redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('ZREM', KEYS[3], ARGV[1])
            redis.call('HSET', KEYS[1], 'state', 'LEFT')
            if redis.call('GET', KEYS[4]) == ARGV[1] then redis.call('DEL', KEYS[4]) end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisTatkalQueueStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String join(long journeyId, String username, String entryId, Instant joinedAt, long entryTtlSeconds) {
        String base = base(journeyId);
        return redis.execute(JOIN_SCRIPT, List.of(
                        base + "waiting", base + "sequence", userEntryKey(journeyId, username),
                        entryKey(journeyId, entryId), base + "admitted"),
                Long.toString(journeyId), entryId, Long.toString(joinedAt.toEpochMilli()),
                username, entryPrefix(journeyId), joinedAt.toString(), Long.toString(entryTtlSeconds));
    }

    public long promote(long journeyId, int capacity, Instant now, long admissionTtlSeconds, String instanceId) {
        String base = base(journeyId);
        Long result = redis.execute(ADMIT_SCRIPT,
                List.of(base + "waiting", base + "admitted", base + "admission-sequence"),
                Integer.toString(capacity), Long.toString(now.toEpochMilli()),
                Long.toString(admissionTtlSeconds), Long.toString(entryTtlSeconds(admissionTtlSeconds)),
                entryPrefix(journeyId), instanceId);
        return result == null ? 0 : result;
    }

    public Optional<TatkalQueueEntry> get(long journeyId, String entryId, Instant now) {
        String base = base(journeyId);
        String key = entryKey(journeyId, entryId);
        Map<Object, Object> values = redis.opsForHash().entries(key);
        if (values.isEmpty()) return Optional.empty();
        String state = value(values, "state");
        Instant until = instant(values, "admittedUntil");
        if ("ADMITTED".equals(state) && until != null && !until.isAfter(now)) {
            redis.execute(new DefaultRedisScript<>("""
                    if redis.call('HGET', KEYS[1], 'state') == 'ADMITTED' then
                      redis.call('HSET', KEYS[1], 'state', 'EXPIRED')
                    end
                    redis.call('ZREM', KEYS[2], ARGV[1])
                    return 1
                    """, Long.class), List.of(key, base + "admitted"), entryId);
            state = "EXPIRED";
        }
        Instant heldUntil = instantMillis(values, "heldUntil");
        if ("HELD".equals(state) && heldUntil != null && !heldUntil.isAfter(now)) {
            redis.opsForHash().put(key, "state", "EXPIRED");
            state = "EXPIRED";
        }
        return Optional.of(new TatkalQueueEntry(
                entryId, journeyId, value(values, "username"), state, instant(values, "joinedAt"),
                longValue(values, "sequence"), value(values, "admissionId"),
                instantMillis(values, "admittedAt"), until, nullableLong(values, "heldSeatId")));
    }

    public Optional<String> findEntryId(long journeyId, String username) {
        return Optional.ofNullable(redis.opsForValue().get(userEntryKey(journeyId, username)));
    }

    public long incrementRateLimit(long journeyId, String username, String operation, long windowMillis) {
        String key = base(journeyId) + "rate:" + operation + ":" + username;
        Long count = redis.execute(RATE_LIMIT_SCRIPT, List.of(key), Long.toString(windowMillis));
        return count == null ? Long.MAX_VALUE : count;
    }

    public Optional<Long> position(long journeyId, String entryId) {
        Long rank = redis.opsForZSet().rank(base(journeyId) + "waiting", entryId);
        return rank == null ? Optional.empty() : Optional.of(rank + 1);
    }

    public boolean claimHold(long journeyId, String entryId, String username, String admissionId,
                             long seatId, Instant now) {
        String result = redis.execute(CLAIM_HOLD_SCRIPT, List.of(entryKey(journeyId, entryId)),
                username, admissionId, Long.toString(now.toEpochMilli()), Long.toString(seatId));
        return "CLAIMED".equals(result);
    }

    public boolean completeHold(long journeyId, String entryId, String username, Instant heldUntil) {
        long ttl = Math.max(86_400, heldUntil.getEpochSecond() - Instant.now().getEpochSecond() + 86_400);
        return updateState(journeyId, entryId, username, "HOLDING", "HELD", true,
                heldUntil.toEpochMilli(), ttl);
    }

    public boolean releaseHoldClaim(long journeyId, String entryId, String username) {
        return updateState(journeyId, entryId, username, "HOLDING", "ADMITTED", false, 0, 0);
    }

    public boolean leave(long journeyId, String entryId, String username) {
        String base = base(journeyId);
        Long result = redis.execute(LEAVE_SCRIPT, List.of(
                        entryKey(journeyId, entryId), base + "waiting", base + "admitted",
                        userEntryKey(journeyId, username)), entryId);
        return result != null && result == 1;
    }

    private boolean updateState(long journeyId, String entryId, String username, String from, String to,
                                boolean releaseCapacity, long heldUntil, long entryTtlSeconds) {
        String base = base(journeyId);
        Long result = redis.execute(UPDATE_STATE_SCRIPT,
                List.of(entryKey(journeyId, entryId), base + "admitted", userEntryKey(journeyId, username)),
                from, to, releaseCapacity ? "release-capacity" : "keep-capacity", entryId,
                Long.toString(heldUntil), Long.toString(entryTtlSeconds));
        return result != null && result == 1;
    }

    private static String base(long journeyId) {
        return "tatkal:{" + journeyId + "}:";
    }

    private static String entryPrefix(long journeyId) {
        return base(journeyId) + "entry:";
    }

    private static String entryKey(long journeyId, String entryId) {
        return entryPrefix(journeyId) + entryId;
    }

    private static String userEntryKey(long journeyId, String username) {
        return base(journeyId) + "user:" + username;
    }

    private static long entryTtlSeconds(long admissionTtlSeconds) {
        return Math.max(86_400, admissionTtlSeconds * 4);
    }

    private static String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    private static Instant instant(Map<Object, Object> values, String key) {
        String value = value(values, key);
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException ignored) {
            return Instant.ofEpochMilli(Long.parseLong(value));
        }
    }

    private static Instant instantMillis(Map<Object, Object> values, String key) {
        String value = value(values, key);
        return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value));
    }

    private static long longValue(Map<Object, Object> values, String key) {
        String value = value(values, key);
        return value == null ? 0 : Long.parseLong(value);
    }

    private static Long nullableLong(Map<Object, Object> values, String key) {
        String value = value(values, key);
        return value == null ? null : Long.valueOf(value);
    }
}
