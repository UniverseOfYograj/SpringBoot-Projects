package in.yograj.learnspringboot.railway_booking.security;

import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter for public PNR status lookups to prevent brute-force 10-digit enumeration attacks.
 */
@Component
public class PnrRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(PnrRateLimiter.class);

    private final int maxRequestsPerWindow;
    private final long windowDurationMs;
    private final ConcurrentHashMap<String, RequestCounter> counters = new ConcurrentHashMap<>();

    public PnrRateLimiter(
            @Value("${railway.security.pnr.max-requests-per-window:30}") int maxRequestsPerWindow,
            @Value("${railway.security.pnr.window-duration-seconds:60}") long windowDurationSeconds) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.windowDurationMs = windowDurationSeconds * 1000L;
    }

    private static class RequestCounter {
        int count;
        long windowStart;

        RequestCounter(long now) {
            this.count = 1;
            this.windowStart = now;
        }
    }

    public synchronized void checkAllowed(String clientIp) {
        long now = System.currentTimeMillis();
        prune(now);

        String key = clientIp != null ? clientIp.trim() : "unknown";
        RequestCounter counter = counters.get(key);

        if (counter == null || (now - counter.windowStart) > windowDurationMs) {
            counters.put(key, new RequestCounter(now));
            return;
        }

        if (counter.count >= maxRequestsPerWindow) {
            long remainingSeconds = Math.max(1, (windowDurationMs - (now - counter.windowStart) + 999) / 1000);
            log.warn("PNR lookup rate limit exceeded for IP {}: {} requests in window. Blocked for {}s",
                    key, counter.count, remainingSeconds);
            throw new BadRequestException("Too many PNR enquiries from your network. Please retry in "
                    + remainingSeconds + " seconds.");
        }

        counter.count++;
    }

    public synchronized void resetAll() {
        counters.clear();
    }

    private void prune(long now) {
        counters.entrySet().removeIf(entry -> (now - entry.getValue().windowStart) > (windowDurationMs * 2));
    }
}
