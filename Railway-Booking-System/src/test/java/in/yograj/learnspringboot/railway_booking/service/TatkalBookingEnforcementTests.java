package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.dto.BookingMode;
import in.yograj.learnspringboot.railway_booking.dto.CreateBookingRequestDTO;
import in.yograj.learnspringboot.railway_booking.entity.JourneySeatInventory;
import in.yograj.learnspringboot.railway_booking.entity.JourneyStop;
import in.yograj.learnspringboot.railway_booking.entity.SeatAllocation;
import in.yograj.learnspringboot.railway_booking.entity.User;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.repository.BookingRepository;
import in.yograj.learnspringboot.railway_booking.repository.JourneySeatInventoryRepository;
import in.yograj.learnspringboot.railway_booking.repository.PaymentTransactionRepository;
import in.yograj.learnspringboot.railway_booking.repository.SeatAllocationRepository;
import in.yograj.learnspringboot.railway_booking.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TatkalBookingEnforcementTests {
    @Test
    void bookingCannotUseTatkalModeWithoutPersistedAdmissionLink() {
        BookingRepository bookingRepository = mock(BookingRepository.class);
        JourneySeatInventoryRepository inventoryRepository = mock(JourneySeatInventoryRepository.class);
        SeatAllocationRepository allocationRepository = mock(SeatAllocationRepository.class);
        SeatInventoryService seatInventoryService = mock(SeatInventoryService.class);
        UserRepository userRepository = mock(UserRepository.class);
        PaymentTransactionRepository paymentRepository = mock(PaymentTransactionRepository.class);
        TatkalAdmissionPassService passService = mock(TatkalAdmissionPassService.class);
        User user = mock(User.class);
        JourneySeatInventory inventory = mock(JourneySeatInventory.class);
        JourneyStop boardingStop = mock(JourneyStop.class);
        JourneyStop alightingStop = mock(JourneyStop.class);
        SeatAllocation allocation = mock(SeatAllocation.class);

        when(userRepository.findByUsername("traveler")).thenReturn(Optional.of(user));
        when(inventoryRepository.findByJourneyAndSeatForUpdate(7L, 3L)).thenReturn(Optional.of(inventory));
        when(inventory.getInventoryId()).thenReturn(9L);
        when(seatInventoryService.resolveSegment(7L, null, null))
                .thenReturn(new SeatInventoryService.JourneySegment(boardingStop, alightingStop));
        when(allocationRepository.findByInventory_InventoryId(9L)).thenReturn(List.of(allocation));
        when(allocation.getStatus()).thenReturn("HOLD");
        when(allocation.getHoldExpiresAt()).thenReturn(LocalDateTime.now().plusMinutes(5));
        when(allocation.getBoardingStop()).thenReturn(boardingStop);
        when(allocation.getAlightingStop()).thenReturn(alightingStop);
        when(boardingStop.getJourneyStopId()).thenReturn(11L);
        when(alightingStop.getJourneyStopId()).thenReturn(12L);
        when(allocation.getHoldOwner()).thenReturn(user);
        when(user.getUserId()).thenReturn(1L);
        when(allocation.getBookingMode()).thenReturn(BookingMode.TATKAL);
        when(allocation.getAdmissionEntryId()).thenReturn(null);

        BookingService bookingService = new BookingService(bookingRepository, inventoryRepository,
                allocationRepository, seatInventoryService, userRepository, paymentRepository,
                new TatkalJourneyPolicy("7"), passService);

        assertThrows(BadRequestException.class, () -> bookingService.createBooking(
                new CreateBookingRequestDTO(7L, 3L, null, null, BookingMode.TATKAL), "traveler"));
        verify(bookingRepository, never()).save(any());
    }
}
