package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.dto.BookingResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.CreateBookingRequestDTO;
import in.yograj.learnspringboot.railway_booking.entity.Booking;
import in.yograj.learnspringboot.railway_booking.entity.JourneySeatInventory;
import in.yograj.learnspringboot.railway_booking.entity.SeatAllocation;
import in.yograj.learnspringboot.railway_booking.entity.User;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.repository.BookingRepository;
import in.yograj.learnspringboot.railway_booking.repository.JourneySeatInventoryRepository;
import in.yograj.learnspringboot.railway_booking.repository.SeatAllocationRepository;
import in.yograj.learnspringboot.railway_booking.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class BookingService {

    private static final String HOLD = "HOLD";
    private static final String PENDING_PAYMENT = "PENDING_PAYMENT";

    private final BookingRepository bookingRepository;
    private final JourneySeatInventoryRepository inventoryRepository;
    private final SeatAllocationRepository allocationRepository;
    private final SeatInventoryService seatInventoryService;
    private final UserRepository userRepository;

    public BookingService(BookingRepository bookingRepository,
                          JourneySeatInventoryRepository inventoryRepository,
                          SeatAllocationRepository allocationRepository,
                          SeatInventoryService seatInventoryService,
                          UserRepository userRepository) {
        this.bookingRepository = bookingRepository;
        this.inventoryRepository = inventoryRepository;
        this.allocationRepository = allocationRepository;
        this.seatInventoryService = seatInventoryService;
        this.userRepository = userRepository;
    }

    @Transactional
    public BookingResponseDTO createBooking(CreateBookingRequestDTO request, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));

        // Parent run-seat row lock same seat ke holds aur booking decisions ko serialize karta hai.
        JourneySeatInventory inventory = inventoryRepository
                .findByJourneyAndSeatForUpdate(request.journeyId(), request.seatId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Seat inventory not found for journeyId=" + request.journeyId()
                                + ", seatId=" + request.seatId()));
        seatInventoryService.normalizeLegacyHold(inventory);
        SeatInventoryService.JourneySegment segment = seatInventoryService.resolveSegment(
                request.journeyId(), request.boardingJourneyStopId(), request.alightingJourneyStopId());

        LocalDateTime now = LocalDateTime.now();
        SeatAllocation allocation = allocationRepository
                .findByInventory_InventoryId(inventory.getInventoryId()).stream()
                .filter(candidate -> HOLD.equalsIgnoreCase(candidate.getStatus()))
                .filter(candidate -> candidate.getHoldExpiresAt() != null
                        && candidate.getHoldExpiresAt().isAfter(now))
                .filter(candidate -> candidate.getBoardingStop().getJourneyStopId()
                        .equals(segment.boardingStop().getJourneyStopId()))
                .filter(candidate -> candidate.getAlightingStop().getJourneyStopId()
                        .equals(segment.alightingStop().getJourneyStopId()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "An active hold for this exact travel segment is required"));

        // Main hold owner verify karta hu, taaki dusre user ka segment hold booking me na badle.
        if (!allocation.getHoldOwner().getUserId().equals(user.getUserId())) {
            throw new AccessDeniedException("Seat hold belongs to another user");
        }

        if (bookingRepository.existsBySeatAllocation_SeatAllocationIdAndBookingStatus(
                allocation.getSeatAllocationId(), PENDING_PAYMENT)) {
            throw new BadRequestException("A pending booking already exists for this seat hold");
        }

        Booking booking = new Booking();
        booking.setUser(user);
        booking.setJourney(inventory.getJourney());
        booking.setCoach(inventory.getCoach());
        booking.setSeat(inventory.getSeat());
        booking.setInventory(inventory);
        booking.setSeatAllocation(allocation);
        booking.setBookingRef("RB-" + UUID.randomUUID().toString().replace("-", ""));
        booking.setBookingStatus(PENDING_PAYMENT);
        booking.setPaymentStatus("PENDING");
        // Fare abhi model nahi hua, isliye amount client se nahi leta aur fake fare bhi set nahi karta.
        booking.setAmount(null);
        booking.setCreatedAt(now);

        Booking savedBooking = bookingRepository.save(booking);
        // Payment/confirmation tak allocation HOLD hi rehti hai; yahan BOOKED transition nahi hota.
        return toResponse(savedBooking);
    }

    private BookingResponseDTO toResponse(Booking booking) {
        return new BookingResponseDTO(
                booking.getBookingId(),
                booking.getBookingRef(),
                booking.getJourney().getJourneyId(),
                booking.getSeat().getSeatId(),
                booking.getCoach().getCoachNumber(),
                booking.getSeat().getSeatNumber(),
                booking.getBookingStatus(),
                booking.getPaymentStatus(),
                booking.getAmount(),
                booking.getCreatedAt()
        );
    }
}
