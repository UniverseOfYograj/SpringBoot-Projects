package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.exception.QueueRateLimitException;
import in.yograj.learnspringboot.railway_booking.exception.QueueUnavailableException;
import in.yograj.learnspringboot.railway_booking.repository.JourneyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TatkalQueueServiceTests {
    private RedisTatkalQueueStore store;
    private TatkalAdmissionPassService passService;
    private JourneyRepository journeyRepository;
    private TatkalQueueService service;

    @BeforeEach
    void setUp() {
        store = mock(RedisTatkalQueueStore.class);
        passService = mock(TatkalAdmissionPassService.class);
        journeyRepository = mock(JourneyRepository.class);
        service = new TatkalQueueService(store, passService, journeyRepository,
                new TatkalJourneyPolicy("7,8"));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "capacity", 2);
        ReflectionTestUtils.setField(service, "admissionTtlSeconds", 300L);
        ReflectionTestUtils.setField(service, "entryTtlSeconds", 86_400L);
        when(journeyRepository.existsById(7L)).thenReturn(true);
        when(store.incrementRateLimit(anyLong(), anyString(), anyString(), anyLong())).thenReturn(1L);
    }

    @Test
    void joinUsesServerEntryIdAndReturnsQueuePosition() {
        when(store.join(eq(7L), eq("traveler"), anyString(), any(Instant.class), eq(86_400L)))
                .thenReturn("entry-1");
        when(store.get(eq(7L), eq("entry-1"), any(Instant.class)))
                .thenReturn(Optional.of(new TatkalQueueEntry("entry-1", 7L, "traveler",
                        "WAITING", Instant.now(), 1, null, null, null, null)));
        when(store.position(7L, "entry-1")).thenReturn(Optional.of(1L));

        var response = service.join(7L, "traveler");

        assertEquals("entry-1", response.queueEntryId());
        assertEquals("WAITING", response.status());
        assertEquals(1L, response.position());
        verify(store).promote(eq(7L), eq(2), any(Instant.class), eq(300L), anyString());
    }

    @Test
    void joinRejectsUnconfiguredTatkalJourney() {
        assertThrows(QueueUnavailableException.class, () -> service.join(99L, "traveler"));
        verifyNoInteractions(store);
    }

    @Test
    void redisFailureFailsClosedAsServiceUnavailable() {
        when(journeyRepository.existsById(7L)).thenReturn(true);
        when(store.incrementRateLimit(anyLong(), anyString(), anyString(), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("redis unavailable"));

        assertThrows(QueueUnavailableException.class, () -> service.join(7L, "traveler"));
        verify(store, never()).join(anyLong(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void rateLimitedQueueRequestsAreRejected() {
        when(store.incrementRateLimit(anyLong(), anyString(), eq("join"), anyLong())).thenReturn(11L);

        assertThrows(QueueRateLimitException.class, () -> service.join(7L, "traveler"));
        verify(store, never()).join(anyLong(), anyString(), anyString(), any(), anyLong());
    }
}
