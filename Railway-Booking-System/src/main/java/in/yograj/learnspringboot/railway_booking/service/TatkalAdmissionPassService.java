package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.exception.InvalidAdmissionPassException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class TatkalAdmissionPassService {
    private static final String ISSUER = "railora-tatkal";
    private static final String AUDIENCE = "railora-booking";

    @Value("${tatkal.queue.enabled:false}")
    private boolean enabled;

    @Value("${tatkal.queue.admission-secret:${TATKAL_ADMISSION_SECRET:}}")
    private String admissionSecret;

    public void validateConfiguration() {
        if (enabled && (admissionSecret == null
                || admissionSecret.getBytes(StandardCharsets.UTF_8).length < 32)) {
            throw new IllegalStateException(
                    "Tatkal queue is enabled but TATKAL_ADMISSION_SECRET is missing or shorter than 32 bytes");
        }
    }

    public String issue(TatkalQueueEntry entry) {
        ensureEnabled();
        SecretKey key = signingKey();
        return Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(entry.username())
                .id(entry.admissionId())
                .claim("journeyId", entry.journeyId())
                .claim("queueEntryId", entry.entryId())
                .issuedAt(Date.from(entry.admittedAt()))
                .expiration(Date.from(entry.admittedUntil()))
                .signWith(key)
                .compact();
    }

    public AdmissionClaims verify(String token, String username, long journeyId, boolean allowExpired) {
        ensureEnabled();
        return verifyClaims(token, username, journeyId, allowExpired);
    }

    public AdmissionClaims verifyForExistingHold(String token, String username, long journeyId) {
        return verifyClaims(token, username, journeyId, true);
    }

    private AdmissionClaims verifyClaims(String token, String username, long journeyId, boolean allowExpired) {
        if (token == null || token.isBlank()) {
            throw new InvalidAdmissionPassException("Tatkal admission pass is required");
        }
        try {
            var parser = Jwts.parser()
                    .verifyWith(signingKey())
                    .requireIssuer(ISSUER)
                    .clock(() -> allowExpired ? new Date(0) : new Date())
                    .build();
            Claims claims = parser.parseSignedClaims(token).getPayload();
            Object claimJourneyId = claims.get("journeyId");
            Object claimEntryId = claims.get("queueEntryId");
            if (!claims.getAudience().contains(AUDIENCE)
                    || !username.equals(claims.getSubject())
                    || !(claimJourneyId instanceof Number journeyClaim)
                    || journeyClaim.longValue() != journeyId
                    || !(claimEntryId instanceof String entryId)
                    || entryId.isBlank()
                    || claims.getId() == null
                    || claims.getExpiration() == null
                    || (!allowExpired && !claims.getExpiration().toInstant().isAfter(Instant.now()))) {
                throw new InvalidAdmissionPassException("Tatkal admission pass is invalid or expired");
            }
            return new AdmissionClaims(entryId, claims.getId(), claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException exception) {
            throw new InvalidAdmissionPassException("Tatkal admission pass is invalid or expired", exception);
        }
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw new in.yograj.learnspringboot.railway_booking.exception.QueueUnavailableException(
                    "Tatkal queue is not enabled");
        }
    }

    private SecretKey signingKey() {
        if (admissionSecret == null || admissionSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("TATKAL_ADMISSION_SECRET must be configured with at least 32 bytes");
        }
        return Keys.hmacShaKeyFor(admissionSecret.getBytes(StandardCharsets.UTF_8));
    }

    public record AdmissionClaims(String queueEntryId, String admissionId, Instant expiresAt) {
    }
}
