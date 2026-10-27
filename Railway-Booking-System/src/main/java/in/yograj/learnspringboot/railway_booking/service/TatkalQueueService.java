package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.dto.TatkalQueueResponse;
import in.yograj.learnspringboot.railway_booking.exception.InvalidAdmissionPassException;
import in.yograj.learnspringboot.railway_booking.exception.QueueUnavailableException;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.exception.QueueRateLimitException;
import in.yograj.learnspringboot.railway_booking.repository.JourneyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

@Service
public class TatkalQueueService {
    private static final Logger logger = LoggerFactory.getLogger(TatkalQueueService.class);

    private final RedisTatkalQueueStore store;
    private final TatkalAdmissionPassService passService;
    private final JourneyRepository journeyRepository;
    private final TatkalJourneyPolicy journeyPolicy;
    private final String instanceId = UUID.randomUUID().toString();

    @Value("${tatkal.queue.enabled:false}")
    private boolean enabled;

    @Value("${tatkal.queue.capacity:100}")
    private int capacity;

    @Value("${tatkal.queue.admission-ttl-seconds:300}")
    private long admissionTtlSeconds;

    @Value("${tatkal.queue.entry-ttl-seconds:86400}")
    private long entryTtlSeconds;

    public TatkalQueueService(RedisTatkalQueueStore store, TatkalAdmissionPassService passService,
                              JourneyRepository journeyRepository, TatkalJourneyPolicy journeyPolicy) {
        this.store = store;
        this.passService = passService;
        this.journeyRepository = journeyRepository;
        this.journeyPolicy = journeyPolicy;
    }

    public TatkalQueueResponse join(long journeyId, String username) {
        ensureEnabled();
        if (journeyId <= 0 || username == null || username.isBlank()) {
            throw new IllegalArgumentException("A valid journey and authenticated user are required");
        }
        journeyPolicy.requireTatkalJourney(journeyId);
        if (!journeyRepository.existsById(journeyId)) {
            throw new ResourceNotFoundException("Journey not found: " + journeyId);
        }
        try {
            enforceRateLimit(journeyId, username, "join", 10, 60_000);
            Instant now = Instant.now();
            String entryId = store.join(journeyId, username, UUID.randomUUID().toString(), now, entryTtlSeconds);
            promote(journeyId, now);
            return response(requireOwnedEntry(journeyId, entryId, username, now));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public TatkalQueueResponse status(long journeyId, String username) {
        ensureEnabled();
        journeyPolicy.requireTatkalJourney(journeyId);
        try {
            enforceRateLimit(journeyId, username, "status", 120, 60_000);
            Instant now = Instant.now();
            promote(journeyId, now);
            String entryId = store.findEntryId(journeyId, username)
                    .orElseThrow(() -> new IllegalArgumentException("No active queue entry was found"));
            return response(requireOwnedEntry(journeyId, entryId, username, now));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public void leave(long journeyId, String username) {
        ensureEnabled();
        journeyPolicy.requireTatkalJourney(journeyId);
        try {
            enforceRateLimit(journeyId, username, "leave", 10, 60_000);
            String entryId = store.findEntryId(journeyId, username)
                    .orElseThrow(() -> new IllegalArgumentException("No active queue entry was found"));
            if (!store.leave(journeyId, entryId, username)) {
                TatkalQueueEntry entry = requireOwnedEntry(journeyId, entryId, username, Instant.now());
                if ("HOLDING".equals(entry.status()) || "HELD".equals(entry.status())) {
                    throw new IllegalStateException("Queue admission cannot be left after a seat hold has been created");
                }
                throw new IllegalStateException("Queue entry is no longer active");
            }
            promote(journeyId, Instant.now());
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public TatkalAdmissionPassService.AdmissionClaims claimSeatHold(
            long journeyId, String username, String token, long seatId) {
        TatkalAdmissionPassService.AdmissionClaims claims =
                passService.verify(token, username, journeyId, false);
        try {
            TatkalQueueEntry entry = requireOwnedEntry(journeyId, claims.queueEntryId(), username, Instant.now());
            if (!"ADMITTED".equals(entry.status())
                    || !claims.admissionId().equals(entry.admissionId())
                    || !store.claimHold(journeyId, entry.entryId(), username, claims.admissionId(), seatId, Instant.now())) {
                throw new InvalidAdmissionPassException("Tatkal admission has expired or was already used");
            }
            return claims;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public void completeSeatHold(long journeyId, String entryId, String username, LocalDateTime holdExpiresAt) {
        try {
            Instant expiresAt = holdExpiresAt.atZone(ZoneId.systemDefault()).toInstant();
            if (!store.completeHold(journeyId, entryId, username, expiresAt)) {
                logger.warn("Could not transition Tatkal entry to HELD after PostgreSQL seat hold committed");
            }
            promote(journeyId, Instant.now());
        } catch (DataAccessException exception) {
            logger.error("Tatkal queue update failed after PostgreSQL seat hold committed; entryId={}",
                    entryId, exception);
        }
    }

    public void releaseSeatHoldClaim(long journeyId, String entryId, String username) {
        try {
            store.releaseHoldClaim(journeyId, entryId, username);
        } catch (DataAccessException exception) {
            logger.warn("Could not release Tatkal queue hold claim; it will expire with its admission lease",
                    exception);
        }
    }

    public Optional<TatkalQueueEntry> getEntry(long journeyId, String entryId, String username) {
        ensureEnabled();
        try {
            return store.get(journeyId, entryId, Instant.now())
                    .filter(entry -> username.equals(entry.username()));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private void promote(long journeyId, Instant now) {
        store.promote(journeyId, capacity, now, admissionTtlSeconds, instanceId);
    }

    private void enforceRateLimit(long journeyId, String username, String operation, long limit, long windowMillis) {
        if (store.incrementRateLimit(journeyId, username, operation, windowMillis) > limit) {
            throw new QueueRateLimitException("Tatkal queue request limit exceeded; retry shortly");
        }
    }

    private TatkalQueueEntry requireOwnedEntry(long journeyId, String entryId, String username, Instant now) {
        TatkalQueueEntry entry = store.get(journeyId, entryId, now)
                .orElseThrow(() -> new IllegalArgumentException("Queue entry was not found or has expired"));
        if (!username.equals(entry.username())) {
            throw new InvalidAdmissionPassException("Queue entry does not belong to the authenticated user");
        }
        return entry;
    }

    private TatkalQueueResponse response(TatkalQueueEntry entry) {
        Long position = "WAITING".equals(entry.status())
                ? store.position(entry.journeyId(), entry.entryId()).orElse(null)
                : null;
        String pass = "ADMITTED".equals(entry.status()) ? passService.issue(entry) : null;
        return new TatkalQueueResponse(entry.entryId(), entry.journeyId(), entry.status(),
                entry.joinedAt(), position, entry.admittedUntil(), pass);
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw new QueueUnavailableException("Tatkal queue is not enabled");
        }
        if (capacity < 1 || admissionTtlSeconds < 1 || entryTtlSeconds < 1) {
            throw new IllegalStateException("Tatkal queue capacity and TTL settings must be positive");
        }
        passService.validateConfiguration();
    }

    private static QueueUnavailableException unavailable(DataAccessException exception) {
        return new QueueUnavailableException("Tatkal queue is temporarily unavailable; no booking attempt was admitted",
                exception);
    }
}
