package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.BookingMode;
import in.yograj.learnspringboot.railway_booking.dto.HoldSeatRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.SeatAvailabilityResponseDTO;
import in.yograj.learnspringboot.railway_booking.exception.InvalidAdmissionPassException;
import in.yograj.learnspringboot.railway_booking.service.SeatInventoryService;
import in.yograj.learnspringboot.railway_booking.service.TatkalAdmissionPassService;
import in.yograj.learnspringboot.railway_booking.service.TatkalJourneyPolicy;
import in.yograj.learnspringboot.railway_booking.service.TatkalQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TatkalHoldEnforcementTests {
    private SeatInventoryService seatInventoryService;
    private TatkalQueueService queueService;
    private SeatInventoryController controller;

    @BeforeEach
    void setUp() {
        seatInventoryService = mock(SeatInventoryService.class);
        queueService = mock(TatkalQueueService.class);
        controller = new SeatInventoryController(seatInventoryService, queueService,
                new TatkalJourneyPolicy("7"));
    }

    @Test
    void tatkalHoldRequiresQueueAdmissionAndPersistsAdmissionLink() {
        var user = new UsernamePasswordAuthenticationToken("traveler", "n/a", List.of());
        when(queueService.claimSeatHold(7L, "traveler", "signed-pass", 12L))
                .thenReturn(new TatkalAdmissionPassService.AdmissionClaims("entry-1", "admission-1",
                        Instant.now().plusSeconds(60)));
        when(seatInventoryService.holdSeat(7L, 12L, 10, 1L, 2L, "traveler",
                BookingMode.TATKAL, "entry-1"))
                .thenReturn(new SeatAvailabilityResponseDTO(1L, 7L, 3L, "B1", 12L, 12,
                        "LOWER", "HOLD", LocalDateTime.now().plusMinutes(10), LocalDateTime.now(),
                        77L, 1L, 2L));

        controller.holdSeat(7L, new HoldSeatRequestDTO(12L, 10, 1L, 2L, BookingMode.TATKAL),
                user, "signed-pass");

        verify(seatInventoryService).holdSeat(7L, 12L, 10, 1L, 2L, "traveler",
                BookingMode.TATKAL, "entry-1");
        verify(queueService).completeSeatHold(eq(7L), eq("entry-1"), eq("traveler"), any());
    }

    @Test
    void tatkalJourneyCannotBeHeldAsGeneralOrWithoutPass() {
        var user = new UsernamePasswordAuthenticationToken("traveler", "n/a", List.of());
        doThrow(new InvalidAdmissionPassException("Tatkal admission pass is required"))
                .when(queueService).claimSeatHold(7L, "traveler", null, 12L);

        assertThrows(InvalidAdmissionPassException.class,
                () -> controller.holdSeat(7L, new HoldSeatRequestDTO(12L, 10, 1L, 2L), user, null));
        assertThrows(InvalidAdmissionPassException.class,
                () -> controller.holdSeat(7L,
                        new HoldSeatRequestDTO(12L, 10, 1L, 2L, BookingMode.TATKAL), user, null));
        verifyNoInteractions(seatInventoryService);
    }
}
