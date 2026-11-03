package in.yograj.learnspringboot.railway_booking.security;

import in.yograj.learnspringboot.railway_booking.exception.LoginRateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, per-application-instance rate limiter for authentication attempts.
 * Protects against brute-force password guessing and distributed credential stuffing.
 * Supports dual-key tracking:
 * 1. IP-level limit: prevents spraying multiple usernames from one client IP.
 * 2. Username-level limit: prevents distributed attacks targeting a single account from multiple IPs.
 * Multi-instance deployments need a shared rate-limit store for cluster-wide enforcement.
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private final int maxAttemptsPerUsername;
    private final int maxAttemptsPerIp;
    private final long blockDurationMs;

    private final ConcurrentHashMap<String, AttemptEntry> attempts = new ConcurrentHashMap<>();

    public LoginRateLimiter(
            @Value("${railway.security.login.max-attempts-username:5}") int maxAttemptsPerUsername,
            @Value("${railway.security.login.max-attempts-ip:10}") int maxAttemptsPerIp,
            @Value("${railway.security.login.block-duration-seconds:60}") long blockDurationSeconds) {
        this.maxAttemptsPerUsername = maxAttemptsPerUsername;
        this.maxAttemptsPerIp = maxAttemptsPerIp;
        this.blockDurationMs = blockDurationSeconds * 1000L;
    }

    private static class AttemptEntry {
        int failureCount;
        int pendingAttempts;
        long firstAttemptTime;
        long blockedUntil;

        AttemptEntry(long now) {
            this.firstAttemptTime = now;
        }
    }

    private String ipKey(String clientIp) {
        return "ip:" + (clientIp != null ? clientIp.trim() : "unknown");
    }

    private String userKey(String username) {
        return "user:" + (username != null ? username.trim().toLowerCase() : "anonymous");
    }

    private String usernameKey(String username) {
        return username == null || username.isBlank() ? null : userKey(username);
    }

    /**
     * Atomically checks the client IP and target account, then reserves a slot before
     * authentication starts so concurrent requests cannot exceed either threshold.
     */
    public synchronized void checkAllowed(String clientIp, String username) {
        long now = System.currentTimeMillis();
        pruneExpiredEntries(now);

        String ipKey = ipKey(clientIp);
        AttemptEntry ipEntry = attempts.get(ipKey);
        rejectIfLimited(
                ipEntry,
                maxAttemptsPerIp,
                "Too many login attempts from this network. Please try again after ",
                clientIp,
                now,
                "IP"
        );

        String usernameKey = usernameKey(username);
        AttemptEntry userEntry = usernameKey == null ? null : attempts.get(usernameKey);
        if (usernameKey != null) {
            rejectIfLimited(
                    userEntry,
                    maxAttemptsPerUsername,
                    "Too many failed login attempts for this account. Please try again after ",
                    username,
                    now,
                    "account"
            );
        }

        reserve(ipKey, ipEntry, now);
        if (usernameKey != null) {
            reserve(usernameKey, userEntry, now);
        }
    }

    /**
     * Completes a reserved attempt as an authentication failure for both keys.
     */
    public synchronized void recordFailure(String clientIp, String username) {
        long now = System.currentTimeMillis();
        completeFailure(ipKey(clientIp), maxAttemptsPerIp, now);

        String usernameKey = usernameKey(username);
        if (usernameKey != null) {
            completeFailure(usernameKey, maxAttemptsPerUsername, now);
        }
    }

    private void completeFailure(String key, int maxAllowed, long now) {
        AttemptEntry entry = attempts.get(key);
        if (entry == null) {
            return;
        }
        entry.pendingAttempts = Math.max(0, entry.pendingAttempts - 1);
        entry.failureCount++;
        if (entry.failureCount >= maxAllowed && entry.blockedUntil <= now) {
            entry.blockedUntil = now + blockDurationMs;
            log.warn("Rate limit triggered for {}: {} failed attempts, blocked for {}ms",
                    key, entry.failureCount, blockDurationMs);
        }
    }

    /**
     * Completes a successful attempt without clearing failures shared by other users
     * at the same IP address. Only the authenticated username's failure state resets.
     */
    public synchronized void recordSuccess(String clientIp, String username) {
        long now = System.currentTimeMillis();
        completeSuccess(ipKey(clientIp), false, now);

        String usernameKey = usernameKey(username);
        if (usernameKey != null) {
            completeSuccess(usernameKey, true, now);
        }
    }

    private void completeSuccess(String key, boolean resetFailures, long now) {
        AttemptEntry entry = attempts.get(key);
        if (entry == null) {
            return;
        }
        entry.pendingAttempts = Math.max(0, entry.pendingAttempts - 1);
        if (resetFailures) {
            entry.failureCount = 0;
            entry.blockedUntil = 0L;
            entry.firstAttemptTime = now;
        }
        if (entry.pendingAttempts == 0 && entry.failureCount == 0) {
            attempts.remove(key, entry);
        }
    }

    private void rejectIfLimited(
            AttemptEntry entry, int maxAllowed, String message, String subject, long now, String subjectType) {
        if (entry == null) {
            return;
        }
        if (now < entry.blockedUntil || entry.failureCount + entry.pendingAttempts >= maxAllowed) {
            long remainingSeconds = entry.blockedUntil > now
                    ? Math.max(1, (entry.blockedUntil - now + 999) / 1000)
                    : 1;
            log.warn("Login rejected: {} {} is rate-limited for {} more seconds",
                    subjectType, subject, remainingSeconds);
            throw new LoginRateLimitException(message + remainingSeconds + " seconds.", remainingSeconds);
        }
    }

    private void reserve(String key, AttemptEntry existing, long now) {
        AttemptEntry entry = existing;
        if (entry == null
                || (now - entry.firstAttemptTime > blockDurationMs
                && now > entry.blockedUntil
                && entry.pendingAttempts == 0)) {
            entry = new AttemptEntry(now);
            attempts.put(key, entry);
        }
        entry.pendingAttempts++;
    }

    public synchronized void resetAll() {
        attempts.clear();
    }

    private void pruneExpiredEntries(long now) {
        long cutoff = now - (blockDurationMs * 2);
        attempts.entrySet().removeIf(entry ->
                entry.getValue().pendingAttempts == 0
                        && entry.getValue().blockedUntil < now
                        && entry.getValue().firstAttemptTime < cutoff);
    }
}
