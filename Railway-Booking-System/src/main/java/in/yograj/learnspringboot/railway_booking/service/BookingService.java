package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.dto.BookingDetailResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.BookingResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.CreateBookingRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PageResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentResponseDTO;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.exception.SeatHoldExpiredException;
import in.yograj.learnspringboot.railway_booking.repository.BookingRepository;
import in.yograj.learnspringboot.railway_booking.repository.JourneySeatInventoryRepository;
import in.yograj.learnspringboot.railway_booking.repository.PaymentTransactionRepository;
import in.yograj.learnspringboot.railway_booking.repository.SeatAllocationRepository;
import in.yograj.learnspringboot.railway_booking.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class BookingService {

    private static final String HOLD = "HOLD";
    private static final String BOOKED = "BOOKED";
    private static final String EXPIRED = "EXPIRED";
    private static final String PENDING_PAYMENT = "PENDING_PAYMENT";
    private static final String CONFIRMED = "CONFIRMED";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    public static final BigDecimal DEFAULT_MOCK_FARE = new BigDecimal("500.00");

    private final BookingRepository bookingRepository;
    private final JourneySeatInventoryRepository inventoryRepository;
    private final SeatAllocationRepository allocationRepository;
    private final SeatInventoryService seatInventoryService;
    private final UserRepository userRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;

    public BookingService(BookingRepository bookingRepository,
                          JourneySeatInventoryRepository inventoryRepository,
                          SeatAllocationRepository allocationRepository,
                          SeatInventoryService seatInventoryService,
                          UserRepository userRepository,
                          PaymentTransactionRepository paymentTransactionRepository) {
        this.bookingRepository = bookingRepository;
        this.inventoryRepository = inventoryRepository;
        this.allocationRepository = allocationRepository;
        this.seatInventoryService = seatInventoryService;
        this.userRepository = userRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
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

    @Transactional(noRollbackFor = SeatHoldExpiredException.class)
    public PaymentResponseDTO processPayment(String bookingRef, PaymentRequestDTO request, String username) {
        if (bookingRef == null || bookingRef.isBlank()) {
            throw new BadRequestException("Booking reference is required");
        }

        PaymentRequestDTO safeRequest = request != null
                ? request
                : new PaymentRequestDTO("MOCK_UPI", null, false);

        // 1. Lock the booking row pessimistically to serialize concurrent payments on this booking
        Booking booking = bookingRepository.findByBookingRefForUpdate(bookingRef)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found for reference: " + bookingRef));

        // 2. Ownership verification
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        if (!isAdmin(user) && !booking.getUser().getUserId().equals(user.getUserId())) {
            throw new AccessDeniedException("You are not authorized to pay for this booking");
        }

        // 3. Acquire pessimistic lock on the parent JourneySeatInventory
        // Coordinates with SeatInventoryService.releaseSeat and holdSeat to prevent race conditions
        inventoryRepository.findByJourneyAndSeatForUpdate(
                booking.getJourney().getJourneyId(), booking.getSeat().getSeatId())
                .orElseThrow(() -> new ResourceNotFoundException("Seat inventory not found"));

        // 4. Idempotency check: same key + same parameters returns cached result; same key + different parameters throws 409
        if (safeRequest.idempotencyKey() != null && !safeRequest.idempotencyKey().isBlank()) {
            String trimmedKey = safeRequest.idempotencyKey().trim();
            Optional<PaymentTransaction> existingTxn = paymentTransactionRepository
                    .findByBookingRefAndIdempotencyKey(bookingRef, trimmedKey);
            if (existingTxn.isPresent()) {
                PaymentTransaction txn = existingTxn.get();

                boolean methodMatches = safeRequest.paymentMethod().equalsIgnoreCase(txn.getPaymentMethod());
                boolean simulateFailureMatches = Boolean.TRUE.equals(safeRequest.simulateFailure())
                        == Boolean.TRUE.equals(txn.getSimulateFailure());

                if (!methodMatches || !simulateFailureMatches) {
                    throw new IllegalStateException("Idempotency key '" + trimmedKey
                            + "' was previously used with different request parameters");
                }

                return new PaymentResponseDTO(
                        txn.getTransactionRef(),
                        booking.getBookingRef(),
                        booking.getBookingId(),
                        txn.getAmount(),
                        txn.getStatus(),
                        booking.getBookingStatus(),
                        txn.getPaymentMethod(),
                        "Idempotent response for transaction " + txn.getTransactionRef(),
                        txn.getCreatedAt()
                );
            }
        }

        // 5. Duplicate payment check: booking is already confirmed
        if (CONFIRMED.equalsIgnoreCase(booking.getBookingStatus())) {
            throw new IllegalStateException("Booking has already been confirmed");
        }

        if ("CANCELLED".equalsIgnoreCase(booking.getBookingStatus())
                || EXPIRED.equalsIgnoreCase(booking.getBookingStatus())) {
            throw new IllegalStateException("Booking cannot be paid in status: " + booking.getBookingStatus());
        }

        // 6. Reload SeatAllocation to observe any committed state from concurrent operations (e.g., releaseSeat)
        if (booking.getSeatAllocation() == null) {
            throw new IllegalStateException("Booking does not have an associated seat allocation");
        }
        SeatAllocation allocation = allocationRepository.findById(
                booking.getSeatAllocation().getSeatAllocationId()
        ).orElseThrow(() -> new IllegalStateException("Associated seat allocation not found"));

        LocalDateTime now = LocalDateTime.now();
        String txnRef = "TXN-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String idempotencyKey = safeRequest.idempotencyKey() != null && !safeRequest.idempotencyKey().isBlank()
                ? safeRequest.idempotencyKey().trim()
                : null;

        BigDecimal amountToCharge = booking.getAmount() != null
                ? booking.getAmount()
                : DEFAULT_MOCK_FARE;
        booking.setAmount(amountToCharge);

        // 7. Check hold expiration
        if (allocation.getHoldExpiresAt() != null && !allocation.getHoldExpiresAt().isAfter(now)) {
            allocation.setStatus(EXPIRED);
            allocation.setUpdatedAt(now);
            allocationRepository.save(allocation);

            booking.setBookingStatus(EXPIRED);
            booking.setPaymentStatus(EXPIRED);
            bookingRepository.save(booking);

            PaymentTransaction expiredTxn = new PaymentTransaction();
            expiredTxn.setBooking(booking);
            expiredTxn.setBookingRef(booking.getBookingRef());
            expiredTxn.setTransactionRef(txnRef);
            expiredTxn.setPaymentMethod(safeRequest.paymentMethod());
            expiredTxn.setAmount(amountToCharge);
            expiredTxn.setStatus(STATUS_FAILED);
            expiredTxn.setFailureReason("Seat hold has expired");
            expiredTxn.setIdempotencyKey(idempotencyKey);
            expiredTxn.setSimulateFailure(safeRequest.simulateFailure());
            expiredTxn.setCreatedAt(now);
            paymentTransactionRepository.save(expiredTxn);

            throw new SeatHoldExpiredException("Seat hold has expired. Booking can no longer be confirmed.");
        }

        if (!HOLD.equalsIgnoreCase(allocation.getStatus())) {
            throw new IllegalStateException("Seat allocation is not on hold (current status: " + allocation.getStatus() + ")");
        }

        // 8. Process payment (mock payment flow)
        if (Boolean.TRUE.equals(safeRequest.simulateFailure())) {
            PaymentTransaction failedTxn = new PaymentTransaction();
            failedTxn.setBooking(booking);
            failedTxn.setBookingRef(booking.getBookingRef());
            failedTxn.setTransactionRef(txnRef);
            failedTxn.setPaymentMethod(safeRequest.paymentMethod());
            failedTxn.setAmount(amountToCharge);
            failedTxn.setStatus(STATUS_FAILED);
            failedTxn.setFailureReason("Simulated payment failure");
            failedTxn.setIdempotencyKey(idempotencyKey);
            failedTxn.setSimulateFailure(safeRequest.simulateFailure());
            failedTxn.setCreatedAt(now);
            paymentTransactionRepository.save(failedTxn);

            booking.setPaymentStatus(STATUS_FAILED);
            bookingRepository.save(booking);

            return new PaymentResponseDTO(
                    txnRef,
                    booking.getBookingRef(),
                    booking.getBookingId(),
                    amountToCharge,
                    STATUS_FAILED,
                    booking.getBookingStatus(), // remains PENDING_PAYMENT (retryable)
                    safeRequest.paymentMethod(),
                    "Payment failed: Simulated payment failure",
                    now
            );
        }

        // Payment Success
        PaymentTransaction successTxn = new PaymentTransaction();
        successTxn.setBooking(booking);
        successTxn.setBookingRef(booking.getBookingRef());
        successTxn.setTransactionRef(txnRef);
        successTxn.setPaymentMethod(safeRequest.paymentMethod());
        successTxn.setAmount(amountToCharge);
        successTxn.setStatus(STATUS_SUCCESS);
        successTxn.setFailureReason(null);
        successTxn.setIdempotencyKey(idempotencyKey);
        successTxn.setSimulateFailure(safeRequest.simulateFailure());
        successTxn.setCreatedAt(now);
        paymentTransactionRepository.save(successTxn);

        // State Transitions: Booking -> CONFIRMED, Allocation -> BOOKED
        booking.setBookingStatus(CONFIRMED);
        booking.setPaymentStatus(STATUS_SUCCESS);
        bookingRepository.save(booking);

        allocation.setStatus(BOOKED);
        allocation.setUpdatedAt(now);
        allocationRepository.save(allocation);

        return new PaymentResponseDTO(
                txnRef,
                booking.getBookingRef(),
                booking.getBookingId(),
                amountToCharge,
                STATUS_SUCCESS,
                CONFIRMED,
                safeRequest.paymentMethod(),
                "Payment successful and booking confirmed",
                now
        );
    }

    @Transactional(readOnly = true)
    public BookingDetailResponseDTO getBookingByRef(String bookingRef, String username) {
        if (bookingRef == null || bookingRef.isBlank()) {
            throw new BadRequestException("Booking reference is required");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));

        Booking booking = bookingRepository.findByBookingRef(bookingRef.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found for reference: " + bookingRef));

        if (!isAdmin(user) && !booking.getUser().getUserId().equals(user.getUserId())) {
            throw new AccessDeniedException("You are not authorized to view this booking");
        }

        return toDetailResponse(booking);
    }

    @Transactional(readOnly = true)
    public PageResponseDTO<BookingDetailResponseDTO> getBookingHistory(int page, int size, String username) {
        if (page < 0) {
            throw new BadRequestException("Page index must not be less than zero");
        }
        if (size < 1) {
            throw new BadRequestException("Page size must be greater than zero");
        }
        if (size > 100) {
            throw new BadRequestException("Page size must not exceed 100");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));

        Pageable pageable = PageRequest.of(
                page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "bookingId")));

        Page<Booking> pageResult = isAdmin(user)
                ? bookingRepository.findAll(pageable)
                : bookingRepository.findByUser_UserId(user.getUserId(), pageable);

        return PageResponseDTO.of(pageResult.map(this::toDetailResponse));
    }

    public BookingDetailResponseDTO toDetailResponse(Booking booking) {
        Journey journey = booking.getJourney();
        Train train = journey != null ? journey.getTrain() : null;
        Station fromStation = journey != null ? journey.getFromStation() : null;
        Station toStation = journey != null ? journey.getToStation() : null;
        Coach coach = booking.getCoach();
        Seat seat = booking.getSeat();
        SeatAllocation allocation = booking.getSeatAllocation();
        User bookingUser = booking.getUser();

        return new BookingDetailResponseDTO(
                booking.getBookingId(),
                booking.getBookingRef(),
                bookingUser != null ? bookingUser.getUserId() : null,
                bookingUser != null ? bookingUser.getUsername() : null,
                journey != null ? journey.getJourneyId() : null,
                train != null ? train.getTrainNumber() : null,
                train != null ? train.getTrainName() : null,
                fromStation != null ? fromStation.getStationCode() : null,
                fromStation != null ? fromStation.getStationName() : null,
                toStation != null ? toStation.getStationCode() : null,
                toStation != null ? toStation.getStationName() : null,
                journey != null ? journey.getJourneyDate() : null,
                journey != null ? journey.getDepartureTime() : null,
                journey != null ? journey.getArrivalTime() : null,
                coach != null ? coach.getCoachId() : null,
                coach != null ? coach.getCoachNumber() : null,
                coach != null ? coach.getCoachType() : null,
                seat != null ? seat.getSeatId() : null,
                seat != null ? seat.getSeatNumber() : null,
                seat != null ? seat.getSeatType() : null,
                allocation != null ? allocation.getSeatAllocationId() : null,
                booking.getBookingStatus(),
                booking.getPaymentStatus(),
                booking.getAmount(),
                booking.getCreatedAt()
        );
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

    private boolean isAdmin(User user) {
        if (user == null || user.getRole() == null) {
            return false;
        }
        String role = user.getRole().trim();
        return "ROLE_ADMIN".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role);
    }
}
