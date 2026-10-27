package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.exception.InvalidAdmissionPassException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TatkalAdmissionPassServiceTests {
    private TatkalAdmissionPassService service;

    @BeforeEach
    void setUp() {
        service = new TatkalAdmissionPassService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "admissionSecret",
                "tatkal-test-admission-secret-at-least-32-bytes");
    }

    @Test
    void signedPassIsBoundToUserAndJourney() {
        String token = service.issue(admittedEntry(7L, "traveler"));

        var claims = service.verify(token, "traveler", 7L, false);

        assertEquals("queue-entry", claims.queueEntryId());
        assertEquals("admission-id", claims.admissionId());
        assertThrows(InvalidAdmissionPassException.class,
                () -> service.verify(token, "other-user", 7L, false));
        assertThrows(InvalidAdmissionPassException.class,
                () -> service.verify(token, "traveler", 8L, false));
    }

    @Test
    void expiredPassCannotAcquireHoldButCanProveAnExistingHold() {
        String token = service.issue(admittedEntry(7L, "traveler",
                Instant.now().minusSeconds(30), Instant.now().minusSeconds(1)));

        assertThrows(InvalidAdmissionPassException.class,
                () -> service.verify(token, "traveler", 7L, false));
        assertEquals("queue-entry",
                service.verifyForExistingHold(token, "traveler", 7L).queueEntryId());
    }

    @Test
    void alteredSignatureIsRejected() {
        String token = service.issue(admittedEntry(7L, "traveler"));
        int signatureStart = token.lastIndexOf('.') + 1;
        char firstSignatureCharacter = token.charAt(signatureStart);
        String tampered = token.substring(0, signatureStart)
                + (firstSignatureCharacter == 'a' ? 'b' : 'a')
                + token.substring(signatureStart + 1);

        assertThrows(InvalidAdmissionPassException.class,
                () -> service.verify(tampered, "traveler", 7L, false));
    }

    private static TatkalQueueEntry admittedEntry(long journeyId, String username) {
        Instant now = Instant.now();
        return admittedEntry(journeyId, username, now, now.plusSeconds(300));
    }

    private static TatkalQueueEntry admittedEntry(long journeyId, String username,
                                                    Instant admittedAt, Instant admittedUntil) {
        return new TatkalQueueEntry("queue-entry", journeyId, username, "ADMITTED",
                Instant.now(), 1, "admission-id", admittedAt, admittedUntil, null);
    }
}
