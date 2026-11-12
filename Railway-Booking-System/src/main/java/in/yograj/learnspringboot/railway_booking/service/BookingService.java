package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.dto.*;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.InvalidAdmissionPassException;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.exception.SeatHoldExpiredException;
import in.yograj.learnspringboot.railway_booking.outbox.OutboxEventService;
import in.yograj.learnspringboot.railway_booking.outbox.event.BookingCancelledEvent;
import in.yograj.learnspringboot.railway_booking.outbox.event.BookingConfirmedEvent;
import in.yograj.learnspringboot.railway_booking.outbox.event.PaymentFailedEvent;
import in.yograj.learnspringboot.railway_booking.repository.BookingRepository;
import in.yograj.learnspringboot.railway_booking.repository.JourneySeatInventoryRepository;
import in.yograj.learnspringboot.railway_booking.repository.PaymentTransactionRepository;
import in.yograj.learnspringboot.railway_booking.repository.SeatAllocationRepository;
import in.yograj.learnspringboot.railway_booking.repository.UserRepository;
import in.yograj.learnspringboot.railway_booking.observability.RailoraMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class BookingService {

    private static final String PENDING_PAYMENT = "PENDING_PAYMENT";
    private static final String CONFIRMED = "CONFIRMED";
    private static final String CANCELLED = "CANCELLED";
    private static final String EXPIRED = "EXPIRED";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_REFUNDED = "REFUNDED";
    private static final String HOLD = "HOLD";
    private static final String BOOKED = "BOOKED";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final BookingRepository bookingRepository;
    private final JourneySeatInventoryRepository inventoryRepository;
    private final SeatAllocationRepository allocationRepository;
    private final SeatInventoryService seatInventoryService;
    private final UserRepository userRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final TatkalJourneyPolicy tatkalJourneyPolicy;
    private final TatkalAdmissionPassService tatkalAdmissionPassService;
    private final OutboxEventService outboxEventService;
    private final FareCalculationService fareCalculationService;

    @Autowired(required = false)
    private RailoraMetrics railoraMetrics;

    public void setRailoraMetrics(RailoraMetrics railoraMetrics) {
        this.railoraMetrics = railoraMetrics;
    }

    private boolean mockPaymentEnabled = true;

    @Value("${railway.payment.simulated-enabled:false}")
    private boolean simulatedPaymentEnabled = false;

    public void setSimulatedPaymentEnabled(boolean simulatedPaymentEnabled) {
        this.simulatedPaymentEnabled = simulatedPaymentEnabled;
    }

    public boolean isSimulatedPaymentEnabled() {
        return this.simulatedPaymentEnabled;
    }

    public void setMockPaymentEnabled(boolean mockPaymentEnabled) {
        this.mockPaymentEnabled = mockPaymentEnabled;
    }

    public boolean isMockPaymentEnabled() {
        return this.mockPaymentEnabled;
    }

    public BookingService(BookingRepository bookingRepository,
                          JourneySeatInventoryRepository inventoryRepository,
                          SeatAllocationRepository allocationRepository,
                          SeatInventoryService seatInventoryService,
                          UserRepository userRepository,
                          PaymentTransactionRepository paymentTransactionRepository,
                          TatkalJourneyPolicy tatkalJourneyPolicy,
                          TatkalAdmissionPassService tatkalAdmissionPassService) {
        this(bookingRepository, inventoryRepository, allocationRepository,
                seatInventoryService, userRepository, paymentTransactionRepository,
                tatkalJourneyPolicy, tatkalAdmissionPassService, null, null);
    }

    public BookingService(BookingRepository bookingRepository,
                          JourneySeatInventoryRepository inventoryRepository,
                          SeatAllocationRepository allocationRepository,
                          SeatInventoryService seatInventoryService,
                          UserRepository userRepository,
                          PaymentTransactionRepository paymentTransactionRepository,
                          TatkalJourneyPolicy tatkalJourneyPolicy,
                          TatkalAdmissionPassService tatkalAdmissionPassService,
                          OutboxEventService outboxEventService) {
        this(bookingRepository, inventoryRepository, allocationRepository, seatInventoryService,
                userRepository, paymentTransactionRepository, tatkalJourneyPolicy,
                tatkalAdmissionPassService, outboxEventService, null);
    }

    @Autowired
    public BookingService(BookingRepository bookingRepository,
                          JourneySeatInventoryRepository inventoryRepository,
                          SeatAllocationRepository allocationRepository,
                          SeatInventoryService seatInventoryService,
                          UserRepository userRepository,
                          PaymentTransactionRepository paymentTransactionRepository,
                          TatkalJourneyPolicy tatkalJourneyPolicy,
                          TatkalAdmissionPassService tatkalAdmissionPassService,
                          OutboxEventService outboxEventService,
                          @Autowired(required = false) FareCalculationService fareCalculationService) {
        this.bookingRepository = bookingRepository;
        this.inventoryRepository = inventoryRepository;
        this.allocationRepository = allocationRepository;
        this.seatInventoryService = seatInventoryService;
        this.userRepository = userRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.tatkalJourneyPolicy = tatkalJourneyPolicy;
        this.tatkalAdmissionPassService = tatkalAdmissionPassService;
        this.outboxEventService = outboxEventService;
        this.fareCalculationService = fareCalculationService;
    }

    @Transactional
    public BookingResponseDTO createBooking(CreateBookingRequestDTO request, String username) {
        return createBooking(request, username, null);
    }

    @Transactional
    public BookingResponseDTO createBooking(CreateBookingRequestDTO request, String username,
                                             String admissionPass) {
        try {
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));

            List<PassengerRequestDTO> passengerRequests = request.passengers();
            if (passengerRequests == null || passengerRequests.isEmpty()) {
                if (request.seatId() == null) {
                    throw new BadRequestException("Either passengers list or seatId must be provided");
                }
                String defaultName = user.getFullName() != null && !user.getFullName().isBlank()
                        ? user.getFullName()
                        : user.getUsername();
                passengerRequests = List.of(new PassengerRequestDTO(defaultName, 30, "MALE", request.seatId(), null));
            }

            if (passengerRequests.isEmpty()) {
                throw new BadRequestException("At least one passenger is required");
            }
            if (passengerRequests.size() > 6) {
                throw new BadRequestException("A maximum of 6 passengers are allowed per booking");
            }

            // Validate passenger fields and check for duplicate seat selection
            Set<Long> seatIdSet = new HashSet<>();
            for (PassengerRequestDTO p : passengerRequests) {
                if (p.fullName() == null || p.fullName().trim().length() < 2) {
                    throw new BadRequestException("Passenger name must be at least 2 characters");
                }
                if (p.fullName().trim().length() > 100) {
                    throw new BadRequestException("Passenger name must not exceed 100 characters");
                }
                if (p.age() == null || p.age() < 1 || p.age() > 125) {
                    throw new BadRequestException("Passenger age must be between 1 and 125");
                }
                if (p.gender() == null || p.gender().isBlank()) {
                    throw new BadRequestException("Passenger gender is required");
                }
                if (p.seatId() == null) {
                    throw new BadRequestException("Seat ID is required for every passenger");
                }
                if (!seatIdSet.add(p.seatId())) {
                    throw new BadRequestException("Duplicate seat ID #" + p.seatId() + " in passenger list");
                }
            }

            SeatInventoryService.JourneySegment segment = seatInventoryService.resolveSegment(
                    request.journeyId(), request.boardingJourneyStopId(), request.alightingJourneyStopId());

            // Sort passenger requests deterministically by seatId to avoid deadlocks across concurrent bookings
            List<PassengerRequestDTO> sortedPassengers = passengerRequests.stream()
                    .sorted(Comparator.comparing(PassengerRequestDTO::seatId))
                    .toList();

            LocalDateTime now = LocalDateTime.now();
            List<JourneySeatInventory> lockedInventories = new ArrayList<>();
            List<SeatAllocation> validAllocations = new ArrayList<>();

            for (PassengerRequestDTO pReq : sortedPassengers) {
                JourneySeatInventory inventory = inventoryRepository
                        .findByJourneyAndSeatForUpdate(request.journeyId(), pReq.seatId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Seat inventory not found for journeyId=" + request.journeyId()
                                        + ", seatId=" + pReq.seatId()));

                // Validate travel class match if provided
                if (request.travelClass() != null && !request.travelClass().isBlank()) {
                    String expectedClass = request.travelClass().trim();
                    String actualClass = inventory.getCoach() != null ? inventory.getCoach().getCoachType() : null;
                    if (actualClass == null || !actualClass.equalsIgnoreCase(expectedClass)) {
                        throw new BadRequestException(
                                "Seat #" + inventory.getSeat().getSeatNumber() + " belongs to class "
                                        + (actualClass != null ? actualClass : "UNKNOWN")
                                        + ", not requested class " + expectedClass);
                    }
                }

                // Validate coach match if provided
                if (request.coachNumber() != null && !request.coachNumber().isBlank()) {
                    String expectedCoach = request.coachNumber().trim();
                    String actualCoach = inventory.getCoach() != null ? inventory.getCoach().getCoachNumber() : null;
                    if (actualCoach == null || !actualCoach.equalsIgnoreCase(expectedCoach)) {
                        throw new BadRequestException(
                                "Seat #" + inventory.getSeat().getSeatNumber() + " belongs to coach "
                                        + (actualCoach != null ? actualCoach : "UNKNOWN")
                                        + ", not requested coach " + expectedCoach);
                    }
                }

                seatInventoryService.normalizeLegacyHold(inventory);

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
                                "An active hold for seat #" + inventory.getSeat().getSeatNumber() + " is required"));

                if (!allocation.getHoldOwner().getUserId().equals(user.getUserId())) {
                    throw new AccessDeniedException("Seat hold for seat #" + inventory.getSeat().getSeatNumber() + " belongs to another user");
                }

                if (allocation.getBookingMode() != request.bookingMode()) {
                    throw new BadRequestException("Booking mode must match the mode used to acquire the seat hold");
                }

                boolean tatkalJourney = tatkalJourneyPolicy != null && tatkalJourneyPolicy.isTatkalJourney(request.journeyId());
                if (tatkalJourney != (allocation.getBookingMode() == BookingMode.TATKAL)) {
                    throw new BadRequestException("Seat hold booking mode is not valid for this journey");
                }
                if (allocation.getBookingMode() == BookingMode.TATKAL
                        && (allocation.getAdmissionEntryId() == null || allocation.getAdmissionEntryId().isBlank())) {
                    throw new BadRequestException("Tatkal booking requires a seat hold created with an admitted queue pass");
                }
                if (allocation.getBookingMode() == BookingMode.TATKAL && admissionPass != null && !admissionPass.isBlank() && tatkalAdmissionPassService != null) {
                    TatkalAdmissionPassService.AdmissionClaims claims = tatkalAdmissionPassService
                            .verifyForExistingHold(admissionPass, username, request.journeyId());
                    if (!claims.queueEntryId().equals(allocation.getAdmissionEntryId())) {
                        throw new InvalidAdmissionPassException("Tatkal pass does not match the active seat hold");
                    }
                }

                if (bookingRepository.existsByAllocationIdAndBookingStatus(
                        allocation.getSeatAllocationId(), PENDING_PAYMENT)) {
                    throw new BadRequestException("A pending booking already exists for this seat hold (ID: " + allocation.getSeatAllocationId() + ")");
                }

                lockedInventories.add(inventory);
                validAllocations.add(allocation);
            }

            // Calculate amount based on dynamic fare calculation service
            JourneySeatInventory leadInventory = lockedInventories.get(0);
            SeatAllocation leadAllocation = validAllocations.get(0);
            String leadClass = leadInventory.getCoach() != null ? leadInventory.getCoach().getCoachType() : null;

            if (fareCalculationService == null) {
                throw new IllegalStateException("FareCalculationService is not available. Booking cannot be priced.");
            }

            FareQuoteResponseDTO quote = fareCalculationService.calculateFareQuote(
                    request.journeyId(),
                    leadClass,
                    sortedPassengers.size(),
                    segment.boardingStop() != null ? segment.boardingStop().getJourneyStopId() : null,
                    segment.alightingStop() != null ? segment.alightingStop().getJourneyStopId() : null
            );
            BigDecimal totalAmount = quote.bookingTotal();
            String fareBreakdownJson = fareCalculationService.serializeFareBreakdown(quote);
            Integer distanceKm = quote.distanceKm();

            Booking booking = new Booking();
            booking.setUser(user);
            booking.setJourney(leadInventory.getJourney());
            booking.setCoach(leadInventory.getCoach());
            booking.setSeat(leadInventory.getSeat());
            booking.setInventory(leadInventory);
            booking.setSeatAllocation(leadAllocation);
            booking.setBookingRef("RB-" + UUID.randomUUID().toString().replace("-", ""));
            booking.setPnr(generateUniquePnr());
            booking.setBookingStatus(PENDING_PAYMENT);
            booking.setPaymentStatus("PENDING");
            booking.setFareBreakdownJson(fareBreakdownJson);
            booking.setDistanceKm(distanceKm);
            booking.setAmount(totalAmount);
            booking.setCreatedAt(now);

            for (int i = 0; i < sortedPassengers.size(); i++) {
                PassengerRequestDTO pReq = sortedPassengers.get(i);
                JourneySeatInventory inv = lockedInventories.get(i);
                SeatAllocation alloc = validAllocations.get(i);

                Passenger passenger = new Passenger();
                passenger.setFullName(pReq.fullName().trim());
                passenger.setAge(pReq.age());
                passenger.setGender(pReq.gender().trim().toUpperCase());
                passenger.setBerthPreference(pReq.berthPreference() != null ? pReq.berthPreference().trim() : null);
                passenger.setSeat(inv.getSeat());
                passenger.setCoach(inv.getCoach());
                passenger.setSeatAllocation(alloc);
                passenger.setCreatedAt(now);

                booking.addPassenger(passenger);
            }

            Booking savedBooking = bookingRepository.save(booking);
            if (railoraMetrics != null) {
                railoraMetrics.recordBookingCreated(admissionPass != null ? "tatkal" : "standard", "success", "none");
            }
            return toResponse(savedBooking);

        } catch (AccessDeniedException ex) {
            if (railoraMetrics != null) {
                railoraMetrics.recordBookingCreated(admissionPass != null ? "tatkal" : "standard", "failure", "unauthorized");
            }
            throw ex;
        } catch (BadRequestException | IllegalStateException | InvalidAdmissionPassException ex) {
            if (railoraMetrics != null) {
                railoraMetrics.recordBookingCreated(admissionPass != null ? "tatkal" : "standard", "failure", "invalid_request");
            }
            throw ex;
        } catch (ResourceNotFoundException ex) {
            if (railoraMetrics != null) {
                railoraMetrics.recordBookingCreated(admissionPass != null ? "tatkal" : "standard", "failure", "not_found");
            }
            throw ex;
        }
    }

    @Transactional(noRollbackFor = SeatHoldExpiredException.class)
    public PaymentResponseDTO processPayment(String bookingRef, PaymentRequestDTO request, String username) {
        if (bookingRef == null || bookingRef.isBlank()) {
            throw new BadRequestException("Booking reference is required");
        }

        PaymentRequestDTO safeRequest = request != null
                ? request
                : new PaymentRequestDTO("MOCK_UPI", null, false);
        String method = safeRequest.paymentMethod() != null ? safeRequest.paymentMethod().toUpperCase() : "MOCK_UPI";
        if ("SIMULATED".equals(method)) {
            if (!simulatedPaymentEnabled) {
                throw new IllegalStateException("Simulated payments are disabled");
            }
            String key = safeRequest.idempotencyKey();
            if (key == null || key.isBlank()) {
                throw new BadRequestException("An idempotency key is required for simulated payments");
            }
            if (Boolean.TRUE.equals(safeRequest.simulateFailure())) {
                return processSimulatedPaymentFailure(bookingRef, key, "Simulated payment failure", username);
            } else {
                return processSimulatedPaymentSuccess(bookingRef, key, username);
            }
        }
        if (!method.startsWith("MOCK_")) {
            throw new BadRequestException("Only mock payment methods are supported by this endpoint");
        }
        if (!mockPaymentEnabled) {
            throw new IllegalStateException("Mock payments are disabled");
        }

        // 1. Lock the booking row pessimistically to serialize concurrent payments on this booking
        Booking booking = bookingRepository.findByBookingRefForUpdate(bookingRef)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found for reference: " + bookingRef));

        // 2. Ownership verification
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        if (!isAdmin(user) && !booking.getUser().getUserId().equals(user.getUserId())) {
            throw new AccessDeniedException("You are not authorized to pay for this booking");
        }

        // 3. Acquire pessimistic lock on all JourneySeatInventory records in deterministic order
        List<Seat> seatsToLock = new ArrayList<>();
        if (booking.getPassengers() != null && !booking.getPassengers().isEmpty()) {
            for (Passenger p : booking.getPassengers()) {
                if (p.getSeat() != null) {
                    seatsToLock.add(p.getSeat());
                }
            }
        } else if (booking.getSeat() != null) {
            seatsToLock.add(booking.getSeat());
        }
        seatsToLock.sort(Comparator.comparing(Seat::getSeatId));
        for (Seat s : seatsToLock) {
            inventoryRepository.findByJourneyAndSeatForUpdate(
                    booking.getJourney().getJourneyId(), s.getSeatId())
                    .orElseThrow(() -> new ResourceNotFoundException("Seat inventory not found for seat ID " + s.getSeatId()));
        }

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

        if (CANCELLED.equalsIgnoreCase(booking.getBookingStatus())
                || EXPIRED.equalsIgnoreCase(booking.getBookingStatus())) {
            throw new IllegalStateException("Booking cannot be paid in status: " + booking.getBookingStatus());
        }

        // 6. Reload all associated SeatAllocations
        List<SeatAllocation> allocations = new ArrayList<>();
        if (booking.getPassengers() != null && !booking.getPassengers().isEmpty()) {
            for (Passenger p : booking.getPassengers()) {
                if (p.getSeatAllocation() != null) {
                    allocations.add(allocationRepository.findById(p.getSeatAllocation().getSeatAllocationId())
                            .orElseThrow(() -> new IllegalStateException("Associated seat allocation not found")));
                }
            }
        } else if (booking.getSeatAllocation() != null) {
            allocations.add(allocationRepository.findById(booking.getSeatAllocation().getSeatAllocationId())
                    .orElseThrow(() -> new IllegalStateException("Associated seat allocation not found")));
        } else {
            throw new IllegalStateException("Booking does not have associated seat allocations");
        }

        LocalDateTime now = LocalDateTime.now();
        String txnRef = "TXN-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String idempotencyKey = safeRequest.idempotencyKey() != null && !safeRequest.idempotencyKey().isBlank()
                ? safeRequest.idempotencyKey().trim()
                : null;

        if (booking.getAmount() == null || booking.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Booking has no valid persisted fare amount. Payment cannot proceed.");
        }
        BigDecimal amountToCharge = booking.getAmount();

        // 7. Check hold expiration across all allocations
        boolean anyExpired = allocations.stream().anyMatch(a -> a.getHoldExpiresAt() != null && !a.getHoldExpiresAt().isAfter(now));
        if (anyExpired) {
            for (SeatAllocation a : allocations) {
                a.setStatus(EXPIRED);
                a.setUpdatedAt(now);
                allocationRepository.save(a);
            }

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

            if (railoraMetrics != null) {
                railoraMetrics.recordPaymentAttempt("MOCK_UPI", "failure", "expired_hold");
            }

            throw new SeatHoldExpiredException("Seat hold has expired. Booking can no longer be confirmed.");
        }

        for (SeatAllocation a : allocations) {
            if (!HOLD.equalsIgnoreCase(a.getStatus())) {
                throw new IllegalStateException("Seat allocation #" + a.getSeatAllocationId() + " is not on hold (current status: " + a.getStatus() + ")");
            }
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

            if (outboxEventService != null) {
                outboxEventService.stageEvent(
                        "PAYMENT",
                        failedTxn.getTransactionRef(),
                        "PAYMENT_FAILED",
                        new PaymentFailedEvent(
                                booking.getBookingRef(),
                                failedTxn.getTransactionRef(),
                                amountToCharge,
                                safeRequest.paymentMethod(),
                                failedTxn.getFailureReason(),
                                now
                        )
                );
            }

            if (railoraMetrics != null) {
                railoraMetrics.recordPaymentAttempt("MOCK_UPI", "failure", "simulation");
            }

            return new PaymentResponseDTO(
                    txnRef,
                    booking.getBookingRef(),
                    booking.getBookingId(),
                    amountToCharge,
                    STATUS_FAILED,
                    booking.getBookingStatus(), // remains PENDING_PAYMENT (retryable)
                    safeRequest.paymentMethod(),
                    "Payment failed: simulated failure requested",
                    now
            );
        }

        // Success path
        PaymentTransaction successTxn = new PaymentTransaction();
        successTxn.setBooking(booking);
        successTxn.setBookingRef(booking.getBookingRef());
        successTxn.setTransactionRef(txnRef);
        successTxn.setPaymentMethod(safeRequest.paymentMethod());
        successTxn.setAmount(amountToCharge);
        successTxn.setStatus(STATUS_SUCCESS);
        successTxn.setIdempotencyKey(idempotencyKey);
        successTxn.setSimulateFailure(false);
        successTxn.setCreatedAt(now);
        paymentTransactionRepository.save(successTxn);

        booking.setBookingStatus(CONFIRMED);
        booking.setPaymentStatus(STATUS_SUCCESS);
        bookingRepository.save(booking);

        for (SeatAllocation a : allocations) {
            a.setStatus(BOOKED);
            a.setUpdatedAt(now);
            allocationRepository.save(a);
        }

        if (outboxEventService != null) {
            outboxEventService.stageEvent(
                    "BOOKING",
                    booking.getBookingRef(),
                    "BOOKING_CONFIRMED",
                    new BookingConfirmedEvent(
                            booking.getBookingRef(),
                            booking.getPnr() != null ? booking.getPnr() : "",
                            booking.getBookingId(),
                            booking.getUser().getUserId(),
                            booking.getUser().getUsername(),
                            booking.getJourney() != null ? booking.getJourney().getJourneyId() : null,
                            amountToCharge,
                            safeRequest.paymentMethod(),
                            successTxn.getTransactionRef(),
                            "CONFIRMED",
                            now
                    )
            );
        }

        if (railoraMetrics != null) {
            railoraMetrics.recordPaymentAttempt("MOCK_UPI", "success", "none");
        }

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

    @Transactional
    public BookingDetailResponseDTO cancelBooking(String bookingRef, String username) {
        if (bookingRef == null || bookingRef.isBlank()) {
            throw new BadRequestException("Booking reference is required");
        }

        // 1. Pessimistic lock on booking to serialize concurrent cancellations or payments
        Booking booking = bookingRepository.findByBookingRefForUpdate(bookingRef.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found for reference: " + bookingRef));

        // 2. Ownership verification: user can cancel only their own booking; admin follows authorization rules
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        if (!isAdmin(user) && !booking.getUser().getUserId().equals(user.getUserId())) {
            if (railoraMetrics != null) {
                railoraMetrics.recordBookingCancellation("failure", "unauthorized");
            }
            throw new AccessDeniedException("You are not authorized to cancel this booking");
        }

        // 3. Status eligibility checks
        if (CANCELLED.equalsIgnoreCase(booking.getBookingStatus())) {
            if (railoraMetrics != null) {
                railoraMetrics.recordBookingCancellation("failure", "already_cancelled");
            }
            throw new IllegalStateException("Booking has already been cancelled");
        }
        if (EXPIRED.equalsIgnoreCase(booking.getBookingStatus())) {
            if (railoraMetrics != null) {
                railoraMetrics.recordBookingCancellation("failure", "invalid_status");
            }
            throw new IllegalStateException("Booking cannot be cancelled in status: " + booking.getBookingStatus());
        }
        if (!CONFIRMED.equalsIgnoreCase(booking.getBookingStatus())
                && !PENDING_PAYMENT.equalsIgnoreCase(booking.getBookingStatus())) {
            if (railoraMetrics != null) {
                railoraMetrics.recordBookingCancellation("failure", "invalid_status");
            }
            throw new IllegalStateException("Booking cannot be cancelled in status: " + booking.getBookingStatus());
        }

        // 4. Verify payment refund preconditions if booking was paid
        PaymentTransaction originalPaymentTxn = null;
        BigDecimal verifiedRefundAmount = null;
        if (STATUS_SUCCESS.equalsIgnoreCase(booking.getPaymentStatus())) {
            List<PaymentTransaction> txns = paymentTransactionRepository.findByBookingRef(booking.getBookingRef());
            List<PaymentTransaction> successfulTxns = txns.stream()
                    .filter(t -> STATUS_SUCCESS.equalsIgnoreCase(t.getStatus()))
                    .toList();

            if (successfulTxns.isEmpty()) {
                throw new IllegalStateException(
                        "Cannot process refund: original successful payment transaction not found for booking " + booking.getBookingRef()
                );
            }
            if (successfulTxns.size() > 1) {
                throw new IllegalStateException(
                        "Cannot process refund: multiple successful payment transactions found for booking " + booking.getBookingRef()
                );
            }

            originalPaymentTxn = successfulTxns.get(0);
            BigDecimal paidAmount = originalPaymentTxn.getAmount();

            if (booking.getAmount() != null && booking.getAmount().compareTo(paidAmount) != 0) {
                throw new IllegalStateException(
                        "Cannot process refund: booking amount (" + booking.getAmount()
                                + ") does not match original payment transaction amount (" + paidAmount
                                + ") for booking " + booking.getBookingRef()
                );
            }

            verifiedRefundAmount = paidAmount;
            if (booking.getAmount() == null) {
                booking.setAmount(verifiedRefundAmount);
            }
        }

        LocalDateTime now = LocalDateTime.now();

        // 5. Lock parent inventories and normalize holds/blocks across all passenger seats
        List<Seat> seatsToRelease = new ArrayList<>();
        if (booking.getPassengers() != null && !booking.getPassengers().isEmpty()) {
            for (Passenger p : booking.getPassengers()) {
                if (p.getSeat() != null) {
                    seatsToRelease.add(p.getSeat());
                }
            }
        } else if (booking.getSeat() != null) {
            seatsToRelease.add(booking.getSeat());
        }

        for (Seat s : seatsToRelease) {
            if (booking.getJourney() != null && s.getSeatId() != null) {
                inventoryRepository.findByJourneyAndSeatForUpdate(
                        booking.getJourney().getJourneyId(), s.getSeatId())
                        .ifPresent(inventory -> {
                            if (HOLD.equalsIgnoreCase(inventory.getStatus()) || "BLOCKED".equalsIgnoreCase(inventory.getStatus())) {
                                inventory.setStatus("AVAILABLE");
                                inventory.setHoldExpiresAt(null);
                                inventory.setHoldOwner(null);
                                inventory.setUpdatedAt(now);
                                inventoryRepository.save(inventory);
                            }
                        });
            }
        }

        // 6. Release seat allocations across all passengers
        if (booking.getPassengers() != null && !booking.getPassengers().isEmpty()) {
            for (Passenger p : booking.getPassengers()) {
                if (p.getSeatAllocation() != null) {
                    SeatAllocation pAlloc = p.getSeatAllocation();
                    pAlloc.setStatus(CANCELLED);
                    pAlloc.setUpdatedAt(now);
                    allocationRepository.save(pAlloc);
                }
            }
        } else if (booking.getSeatAllocation() != null) {
            SeatAllocation allocation = booking.getSeatAllocation();
            allocation.setStatus(CANCELLED);
            allocation.setUpdatedAt(now);
            allocationRepository.save(allocation);
        }

        // 7. Update booking status and process refund record if eligible
        booking.setBookingStatus(CANCELLED);
        if ("PENDING".equalsIgnoreCase(booking.getPaymentStatus())) {
            booking.setPaymentStatus(CANCELLED);
        } else if (STATUS_SUCCESS.equalsIgnoreCase(booking.getPaymentStatus())) {
            boolean razorpayPayment = originalPaymentTxn != null
                    && "RAZORPAY".equalsIgnoreCase(originalPaymentTxn.getPaymentMethod());
            if (razorpayPayment) {
                booking.setPaymentStatus("REFUND_REQUIRED");
            } else {
                booking.setPaymentStatus(STATUS_REFUNDED);

                String refundIdempotencyKey = "REFUND-" + booking.getBookingRef();
                Optional<PaymentTransaction> existingRefund = paymentTransactionRepository
                        .findByBookingRefAndIdempotencyKey(booking.getBookingRef(), refundIdempotencyKey);

                if (existingRefund.isEmpty()) {
                    String paymentMethod = originalPaymentTxn.getPaymentMethod() != null
                            && !originalPaymentTxn.getPaymentMethod().isBlank()
                            ? originalPaymentTxn.getPaymentMethod()
                            : "MOCK_UPI";

                    PaymentTransaction refundTxn = new PaymentTransaction();
                    refundTxn.setBooking(booking);
                    refundTxn.setBookingRef(booking.getBookingRef());
                    refundTxn.setTransactionRef("TXN-REFUND-" + UUID.randomUUID().toString().replace("-", "").toUpperCase());
                    refundTxn.setPaymentMethod(paymentMethod);
                    refundTxn.setAmount(verifiedRefundAmount);
                    refundTxn.setStatus(STATUS_REFUNDED);
                    refundTxn.setIdempotencyKey(refundIdempotencyKey);
                    refundTxn.setSimulateFailure(false);
                    refundTxn.setCreatedAt(now);
                    paymentTransactionRepository.save(refundTxn);
                }
            }
        }

        bookingRepository.save(booking);

        if (railoraMetrics != null) {
            railoraMetrics.recordBookingCancellation("success", "none");
        }

        if (outboxEventService != null) {
            BigDecimal refundAmountForEvent = originalPaymentTxn != null
                    && "RAZORPAY".equalsIgnoreCase(originalPaymentTxn.getPaymentMethod())
                    ? BigDecimal.ZERO
                    : verifiedRefundAmount != null ? verifiedRefundAmount : BigDecimal.ZERO;
            outboxEventService.stageEvent(
                    "BOOKING",
                    booking.getBookingRef(),
                    "BOOKING_CANCELLED",
                    new BookingCancelledEvent(
                            booking.getBookingRef(),
                            booking.getPnr() != null ? booking.getPnr() : "",
                            booking.getBookingId(),
                            booking.getUser().getUserId(),
                            booking.getUser().getUsername(),
                            booking.getBookingStatus(),
                            booking.getPaymentStatus(),
                            refundAmountForEvent,
                            now
                    )
            );
        }

        return toDetailResponse(booking);
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
    public BookingDetailResponseDTO getBookingByPnr(String pnr, String username) {
        if (pnr == null || pnr.isBlank()) {
            throw new BadRequestException("PNR is required");
        }
        String trimmedPnr = pnr.trim();
        if (!trimmedPnr.matches("^\\d{10}$")) {
            throw new BadRequestException("PNR must be exactly 10 numeric digits");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));

        Booking booking = bookingRepository.findByPnr(trimmedPnr)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found for PNR: " + trimmedPnr));

        if (!isAdmin(user) && !booking.getUser().getUserId().equals(user.getUserId())) {
            throw new ResourceNotFoundException("Booking not found for PNR: " + trimmedPnr);
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

        List<PassengerResponseDTO> passengerDTOs = mapPassengers(booking);
        FareComponentDTO fareBreakdown = fareCalculationService != null
                ? fareCalculationService.extractPerPassengerBreakdown(booking.getFareBreakdownJson())
                : null;

        return new BookingDetailResponseDTO(
                booking.getBookingId(),
                booking.getBookingRef(),
                booking.getPnr(),
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
                booking.getCreatedAt(),
                passengerDTOs,
                fareBreakdown,
                booking.getDistanceKm()
        );
    }

    private BookingResponseDTO toResponse(Booking booking) {
        List<PassengerResponseDTO> passengerDTOs = mapPassengers(booking);
        FareComponentDTO fareBreakdown = fareCalculationService != null
                ? fareCalculationService.extractPerPassengerBreakdown(booking.getFareBreakdownJson())
                : null;

        return new BookingResponseDTO(
                booking.getBookingId(),
                booking.getBookingRef(),
                booking.getPnr(),
                booking.getJourney() != null ? booking.getJourney().getJourneyId() : null,
                booking.getSeat() != null ? booking.getSeat().getSeatId() : null,
                booking.getCoach() != null ? booking.getCoach().getCoachNumber() : null,
                booking.getSeat() != null ? booking.getSeat().getSeatNumber() : null,
                booking.getBookingStatus(),
                booking.getPaymentStatus(),
                booking.getAmount(),
                booking.getCreatedAt(),
                passengerDTOs,
                fareBreakdown,
                booking.getDistanceKm()
        );
    }

    private List<PassengerResponseDTO> mapPassengers(Booking booking) {
        if (booking.getPassengers() == null || booking.getPassengers().isEmpty()) {
            return List.of();
        }
        return booking.getPassengers().stream()
                .map(p -> new PassengerResponseDTO(
                        p.getPassengerId(),
                        p.getFullName(),
                        p.getAge(),
                        p.getGender(),
                        p.getBerthPreference(),
                        p.getSeat() != null ? p.getSeat().getSeatId() : null,
                        p.getSeat() != null ? p.getSeat().getSeatNumber() : null,
                        p.getSeat() != null ? p.getSeat().getSeatType() : null,
                        p.getCoach() != null ? p.getCoach().getCoachId() : null,
                        p.getCoach() != null ? p.getCoach().getCoachNumber() : null,
                        p.getCoach() != null ? p.getCoach().getCoachType() : null,
                        p.getSeatAllocation() != null ? p.getSeatAllocation().getSeatAllocationId() : null
                ))
                .toList();
    }

    private String generateUniquePnr() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(10);
            for (int i = 0; i < 10; i++) {
                sb.append(SECURE_RANDOM.nextInt(10));
            }
            String candidate = sb.toString();
            if (!bookingRepository.existsByPnr(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Failed to generate a unique 10-digit PNR after 10 attempts");
    }

    @Transactional(readOnly = true)
    public SimulatedPaymentSummaryDTO getSimulatedPaymentSummary(String bookingRef, String username) {
        if (bookingRef == null || bookingRef.isBlank()) {
            throw new BadRequestException("Booking reference is required");
        }
        if (!simulatedPaymentEnabled) {
            throw new IllegalStateException("Simulated payments are disabled");
        }

        Booking booking = bookingRepository.findByBookingRef(bookingRef.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found for reference: " + bookingRef));

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        if (!isAdmin(user) && !booking.getUser().getUserId().equals(user.getUserId())) {
            throw new AccessDeniedException("You are not authorized to view payment for this booking");
        }

        SeatAllocation allocation = booking.getSeatAllocation();
        LocalDateTime holdExpiresAt = allocation != null ? allocation.getHoldExpiresAt() : null;

        return toSimulatedSummary(booking, holdExpiresAt);
    }

    @Transactional
    public SimulatedPaymentSummaryDTO initiateSimulatedPayment(String bookingRef, String username) {
        if (bookingRef == null || bookingRef.isBlank()) {
            throw new BadRequestException("Booking reference is required");
        }
        if (!simulatedPaymentEnabled) {
            throw new IllegalStateException("Simulated payments are disabled");
        }

        Booking booking = bookingRepository.findByBookingRefForUpdate(bookingRef.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found for reference: " + bookingRef));

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        if (!isAdmin(user) && !booking.getUser().getUserId().equals(user.getUserId())) {
            throw new AccessDeniedException("You are not authorized to pay for this booking");
        }

        if (CONFIRMED.equalsIgnoreCase(booking.getBookingStatus())) {
            throw new IllegalStateException("Booking has already been confirmed");
        }
        if (CANCELLED.equalsIgnoreCase(booking.getBookingStatus())
                || EXPIRED.equalsIgnoreCase(booking.getBookingStatus())) {
            throw new IllegalStateException("Booking cannot be paid in status: " + booking.getBookingStatus());
        }

        if (booking.getAmount() == null || booking.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Booking has no valid persisted fare amount. Simulated payment cannot proceed.");
        }

        // Reload all allocations
        List<SeatAllocation> allocations = new ArrayList<>();
        if (booking.getPassengers() != null && !booking.getPassengers().isEmpty()) {
            for (Passenger p : booking.getPassengers()) {
                if (p.getSeatAllocation() != null) {
                    allocations.add(allocationRepository.findById(p.getSeatAllocation().getSeatAllocationId())
                            .orElseThrow(() -> new IllegalStateException("Associated seat allocation not found")));
                }
            }
        } else if (booking.getSeatAllocation() != null) {
            allocations.add(allocationRepository.findById(booking.getSeatAllocation().getSeatAllocationId())
                    .orElseThrow(() -> new IllegalStateException("Associated seat allocation not found")));
        } else {
            throw new IllegalStateException("Booking does not have associated seat allocations");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean anyExpired = allocations.stream().anyMatch(a -> a.getHoldExpiresAt() != null && !a.getHoldExpiresAt().isAfter(now));
        if (anyExpired) {
            for (SeatAllocation a : allocations) {
                a.setStatus(EXPIRED);
                a.setUpdatedAt(now);
                allocationRepository.save(a);
            }

            booking.setBookingStatus(EXPIRED);
            booking.setPaymentStatus(EXPIRED);
            bookingRepository.save(booking);

            throw new SeatHoldExpiredException("Seat hold has expired. Booking can no longer be confirmed.");
        }

        for (SeatAllocation a : allocations) {
            if (!HOLD.equalsIgnoreCase(a.getStatus())) {
                throw new IllegalStateException("Seat allocation #" + a.getSeatAllocationId() + " is not on hold (current status: " + a.getStatus() + ")");
            }
        }

        return toSimulatedSummary(booking, allocations.get(0).getHoldExpiresAt());
    }

    @Transactional(noRollbackFor = SeatHoldExpiredException.class)
    public PaymentResponseDTO processSimulatedPaymentSuccess(String bookingRef, String idempotencyKey, String username) {
        if (bookingRef == null || bookingRef.isBlank()) {
            throw new BadRequestException("Booking reference is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("An idempotency key is required for simulated payments");
        }
        if (!simulatedPaymentEnabled) {
            throw new IllegalStateException("Simulated payments are disabled");
        }

        String trimmedKey = idempotencyKey.trim();

        // 1. Lock booking row pessimistically
        Booking booking = bookingRepository.findByBookingRefForUpdate(bookingRef.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found for reference: " + bookingRef));

        // 2. Ownership verification
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        if (!isAdmin(user) && !booking.getUser().getUserId().equals(user.getUserId())) {
            throw new AccessDeniedException("You are not authorized to pay for this booking");
        }

        // 3. Idempotency check
        Optional<PaymentTransaction> existingTxn = paymentTransactionRepository
                .findByBookingRefAndIdempotencyKey(booking.getBookingRef(), trimmedKey);
        if (existingTxn.isPresent()) {
            PaymentTransaction txn = existingTxn.get();
            if (STATUS_SUCCESS.equalsIgnoreCase(txn.getStatus())) {
                if (railoraMetrics != null) {
                    railoraMetrics.recordPaymentAttempt("SIMULATED", "idempotent_replay", "none");
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
            } else {
                throw new IllegalStateException("Idempotency key '" + trimmedKey
                        + "' was previously used for a failed payment attempt. A new idempotency key is required to retry.");
            }
        }

        // 4. Duplicate payment check: booking is already confirmed
        if (CONFIRMED.equalsIgnoreCase(booking.getBookingStatus())
                || STATUS_SUCCESS.equalsIgnoreCase(booking.getPaymentStatus())) {
            if (railoraMetrics != null) {
                railoraMetrics.recordPaymentAttempt("SIMULATED", "failure", "invalid_state");
            }
            throw new IllegalStateException("Booking has already been confirmed");
        }

        if (CANCELLED.equalsIgnoreCase(booking.getBookingStatus())
                || EXPIRED.equalsIgnoreCase(booking.getBookingStatus())) {
            if (railoraMetrics != null) {
                railoraMetrics.recordPaymentAttempt("SIMULATED", "failure", "invalid_state");
            }
            throw new IllegalStateException("Booking cannot be paid in status: " + booking.getBookingStatus());
        }

        boolean alreadyPaid = paymentTransactionRepository.findByBookingRef(booking.getBookingRef()).stream()
                .anyMatch(t -> STATUS_SUCCESS.equalsIgnoreCase(t.getStatus()));
        if (alreadyPaid) {
            if (railoraMetrics != null) {
                railoraMetrics.recordPaymentAttempt("SIMULATED", "failure", "invalid_state");
            }
            throw new IllegalStateException("Booking has already been confirmed");
        }

        // 5. Parent inventory locks for all seats in sorted order
        List<Seat> seatsToLock = new ArrayList<>();
        if (booking.getPassengers() != null && !booking.getPassengers().isEmpty()) {
            for (Passenger p : booking.getPassengers()) {
                if (p.getSeat() != null) {
                    seatsToLock.add(p.getSeat());
                }
            }
        } else if (booking.getSeat() != null) {
            seatsToLock.add(booking.getSeat());
        }
        seatsToLock.sort(Comparator.comparing(Seat::getSeatId));
        for (Seat s : seatsToLock) {
            inventoryRepository.findByJourneyAndSeatForUpdate(
                    booking.getJourney().getJourneyId(), s.getSeatId())
                    .orElseThrow(() -> new ResourceNotFoundException("Seat inventory not found"));
        }

        // 6. Seat allocation verification across all passengers
        List<SeatAllocation> allocations = new ArrayList<>();
        if (booking.getPassengers() != null && !booking.getPassengers().isEmpty()) {
            for (Passenger p : booking.getPassengers()) {
                if (p.getSeatAllocation() != null) {
                    allocations.add(allocationRepository.findById(p.getSeatAllocation().getSeatAllocationId())
                            .orElseThrow(() -> new IllegalStateException("Associated seat allocation not found")));
                }
            }
        } else if (booking.getSeatAllocation() != null) {
            allocations.add(allocationRepository.findById(booking.getSeatAllocation().getSeatAllocationId())
                    .orElseThrow(() -> new IllegalStateException("Associated seat allocation not found")));
        } else {
            throw new IllegalStateException("Booking does not have an associated seat allocation");
        }

        LocalDateTime now = LocalDateTime.now();
        String txnRef = "SIM-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();

        if (booking.getAmount() == null || booking.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Booking has no valid persisted fare amount. Simulated payment cannot proceed.");
        }
        BigDecimal amountToCharge = booking.getAmount();

        // 7. Check hold expiration
        boolean anyExpired = allocations.stream().anyMatch(a -> a.getHoldExpiresAt() != null && !a.getHoldExpiresAt().isAfter(now));
        if (anyExpired) {
            for (SeatAllocation a : allocations) {
                a.setStatus(EXPIRED);
                a.setUpdatedAt(now);
                allocationRepository.save(a);
            }

            booking.setBookingStatus(EXPIRED);
            booking.setPaymentStatus(EXPIRED);
            bookingRepository.save(booking);

            PaymentTransaction expiredTxn = new PaymentTransaction();
            expiredTxn.setBooking(booking);
            expiredTxn.setBookingRef(booking.getBookingRef());
            expiredTxn.setTransactionRef(txnRef);
            expiredTxn.setPaymentMethod("SIMULATED");
            expiredTxn.setAmount(amountToCharge);
            expiredTxn.setStatus(STATUS_FAILED);
            expiredTxn.setFailureReason("Seat hold has expired");
            expiredTxn.setIdempotencyKey(trimmedKey);
            expiredTxn.setSimulateFailure(false);
            expiredTxn.setCreatedAt(now);
            paymentTransactionRepository.save(expiredTxn);

            if (railoraMetrics != null) {
                railoraMetrics.recordPaymentAttempt("SIMULATED", "failure", "expired_hold");
            }

            throw new SeatHoldExpiredException("Seat hold has expired. Booking can no longer be confirmed.");
        }

        for (SeatAllocation a : allocations) {
            if (!HOLD.equalsIgnoreCase(a.getStatus())) {
                throw new IllegalStateException("Seat allocation is not on hold (current status: " + a.getStatus() + ")");
            }
        }

        // 8. Success: Persist
        PaymentTransaction txn = new PaymentTransaction();
        txn.setBooking(booking);
        txn.setBookingRef(booking.getBookingRef());
        txn.setTransactionRef(txnRef);
        txn.setPaymentMethod("SIMULATED");
        txn.setAmount(amountToCharge);
        txn.setStatus(STATUS_SUCCESS);
        txn.setIdempotencyKey(trimmedKey);
        txn.setSimulateFailure(false);
        txn.setCreatedAt(now);
        paymentTransactionRepository.save(txn);

        booking.setBookingStatus(CONFIRMED);
        booking.setPaymentStatus(STATUS_SUCCESS);
        bookingRepository.save(booking);

        for (SeatAllocation a : allocations) {
            a.setStatus(BOOKED);
            a.setUpdatedAt(now);
            allocationRepository.save(a);
        }

        if (outboxEventService != null) {
            outboxEventService.stageEvent(
                    "BOOKING",
                    booking.getBookingRef(),
                    "BOOKING_CONFIRMED",
                    new BookingConfirmedEvent(
                            booking.getBookingRef(),
                            booking.getPnr() != null ? booking.getPnr() : "",
                            booking.getBookingId(),
                            booking.getUser().getUserId(),
                            booking.getUser().getUsername(),
                            booking.getJourney() != null ? booking.getJourney().getJourneyId() : null,
                            amountToCharge,
                            "SIMULATED",
                            txn.getTransactionRef(),
                            "CONFIRMED",
                            now
                    )
            );
        }

        if (railoraMetrics != null) {
            railoraMetrics.recordPaymentAttempt("SIMULATED", "success", "none");
        }

        return new PaymentResponseDTO(
                txnRef,
                booking.getBookingRef(),
                booking.getBookingId(),
                amountToCharge,
                STATUS_SUCCESS,
                CONFIRMED,
                "SIMULATED",
                "Simulated payment succeeded",
                now
        );
    }

    @Transactional(noRollbackFor = SeatHoldExpiredException.class)
    public PaymentResponseDTO processSimulatedPaymentFailure(String bookingRef, String idempotencyKey, String reason, String username) {
        if (bookingRef == null || bookingRef.isBlank()) {
            throw new BadRequestException("Booking reference is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("An idempotency key is required for simulated payments");
        }
        if (!simulatedPaymentEnabled) {
            throw new IllegalStateException("Simulated payments are disabled");
        }

        String trimmedKey = idempotencyKey.trim();

        // 1. Lock booking row pessimistically
        Booking booking = bookingRepository.findByBookingRefForUpdate(bookingRef.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found for reference: " + bookingRef));

        // 2. Ownership verification
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        if (!isAdmin(user) && !booking.getUser().getUserId().equals(user.getUserId())) {
            throw new AccessDeniedException("You are not authorized to pay for this booking");
        }

        // 3. Idempotency check
        Optional<PaymentTransaction> existingTxn = paymentTransactionRepository
                .findByBookingRefAndIdempotencyKey(booking.getBookingRef(), trimmedKey);
        if (existingTxn.isPresent()) {
            PaymentTransaction txn = existingTxn.get();
            if (STATUS_FAILED.equalsIgnoreCase(txn.getStatus())) {
                if (railoraMetrics != null) {
                    railoraMetrics.recordPaymentAttempt("SIMULATED", "idempotent_replay", "none");
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
            } else {
                throw new IllegalStateException("Idempotency key '" + trimmedKey
                        + "' was previously used for a successful payment. A new idempotency key is required.");
            }
        }

        // 4. Duplicate payment check
        if (CONFIRMED.equalsIgnoreCase(booking.getBookingStatus())
                || STATUS_SUCCESS.equalsIgnoreCase(booking.getPaymentStatus())) {
            if (railoraMetrics != null) {
                railoraMetrics.recordPaymentAttempt("SIMULATED", "failure", "invalid_state");
            }
            throw new IllegalStateException("Booking has already been confirmed");
        }

        if (CANCELLED.equalsIgnoreCase(booking.getBookingStatus())
                || EXPIRED.equalsIgnoreCase(booking.getBookingStatus())) {
            if (railoraMetrics != null) {
                railoraMetrics.recordPaymentAttempt("SIMULATED", "failure", "invalid_state");
            }
            throw new IllegalStateException("Booking cannot be paid in status: " + booking.getBookingStatus());
        }

        // 5. Parent inventory locks for all seats
        List<Seat> seatsToLock = new ArrayList<>();
        if (booking.getPassengers() != null && !booking.getPassengers().isEmpty()) {
            for (Passenger p : booking.getPassengers()) {
                if (p.getSeat() != null) {
                    seatsToLock.add(p.getSeat());
                }
            }
        } else if (booking.getSeat() != null) {
            seatsToLock.add(booking.getSeat());
        }
        seatsToLock.sort(Comparator.comparing(Seat::getSeatId));
        for (Seat s : seatsToLock) {
            inventoryRepository.findByJourneyAndSeatForUpdate(
                    booking.getJourney().getJourneyId(), s.getSeatId())
                    .orElseThrow(() -> new ResourceNotFoundException("Seat inventory not found"));
        }

        // 6. Reload allocations across all passengers
        List<SeatAllocation> allocations = new ArrayList<>();
        if (booking.getPassengers() != null && !booking.getPassengers().isEmpty()) {
            for (Passenger p : booking.getPassengers()) {
                if (p.getSeatAllocation() != null) {
                    allocations.add(allocationRepository.findById(p.getSeatAllocation().getSeatAllocationId())
                            .orElseThrow(() -> new IllegalStateException("Associated seat allocation not found")));
                }
            }
        } else if (booking.getSeatAllocation() != null) {
            allocations.add(allocationRepository.findById(booking.getSeatAllocation().getSeatAllocationId())
                    .orElseThrow(() -> new IllegalStateException("Associated seat allocation not found")));
        } else {
            throw new IllegalStateException("Booking does not have an associated seat allocation");
        }

        LocalDateTime now = LocalDateTime.now();
        String txnRef = "SIM-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();

        if (booking.getAmount() == null || booking.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Booking has no valid persisted fare amount. Simulated payment cannot proceed.");
        }
        BigDecimal amountToCharge = booking.getAmount();

        // 7. Check hold expiration
        boolean anyExpired = allocations.stream().anyMatch(a -> a.getHoldExpiresAt() != null && !a.getHoldExpiresAt().isAfter(now));
        if (anyExpired) {
            for (SeatAllocation a : allocations) {
                a.setStatus(EXPIRED);
                a.setUpdatedAt(now);
                allocationRepository.save(a);
            }

            booking.setBookingStatus(EXPIRED);
            booking.setPaymentStatus(EXPIRED);
            bookingRepository.save(booking);

            PaymentTransaction expiredTxn = new PaymentTransaction();
            expiredTxn.setBooking(booking);
            expiredTxn.setBookingRef(booking.getBookingRef());
            expiredTxn.setTransactionRef(txnRef);
            expiredTxn.setPaymentMethod("SIMULATED");
            expiredTxn.setAmount(amountToCharge);
            expiredTxn.setStatus(STATUS_FAILED);
            expiredTxn.setFailureReason("Seat hold has expired");
            expiredTxn.setIdempotencyKey(trimmedKey);
            expiredTxn.setSimulateFailure(true);
            expiredTxn.setCreatedAt(now);
            paymentTransactionRepository.save(expiredTxn);

            if (railoraMetrics != null) {
                railoraMetrics.recordPaymentAttempt("SIMULATED", "failure", "expired_hold");
            }

            throw new SeatHoldExpiredException("Seat hold has expired. Booking can no longer be confirmed.");
        }

        for (SeatAllocation a : allocations) {
            if (!HOLD.equalsIgnoreCase(a.getStatus())) {
                throw new IllegalStateException("Seat allocation is not on hold (current status: " + a.getStatus() + ")");
            }
        }

        // 8. Failure: Persist failed PaymentTransaction
        String failureReason = (reason != null && !reason.isBlank()) ? reason.trim() : "Simulated payment failure";

        PaymentTransaction failedTxn = new PaymentTransaction();
        failedTxn.setBooking(booking);
        failedTxn.setBookingRef(booking.getBookingRef());
        failedTxn.setTransactionRef(txnRef);
        failedTxn.setPaymentMethod("SIMULATED");
        failedTxn.setAmount(amountToCharge);
        failedTxn.setStatus(STATUS_FAILED);
        failedTxn.setFailureReason(failureReason);
        failedTxn.setIdempotencyKey(trimmedKey);
        failedTxn.setSimulateFailure(true);
        failedTxn.setCreatedAt(now);
        paymentTransactionRepository.save(failedTxn);

        // Booking payment status set to FAILED, booking status remains PENDING_PAYMENT for retry
        booking.setPaymentStatus(STATUS_FAILED);
        bookingRepository.save(booking);

        if (outboxEventService != null) {
            outboxEventService.stageEvent(
                    "PAYMENT",
                    failedTxn.getTransactionRef(),
                    "PAYMENT_FAILED",
                    new PaymentFailedEvent(
                            booking.getBookingRef(),
                            failedTxn.getTransactionRef(),
                            amountToCharge,
                            "SIMULATED",
                            failedTxn.getFailureReason(),
                            now
                    )
            );
        }

        if (railoraMetrics != null) {
            railoraMetrics.recordPaymentAttempt("SIMULATED", "failure", "simulation");
        }

        return new PaymentResponseDTO(
                txnRef,
                booking.getBookingRef(),
                booking.getBookingId(),
                amountToCharge,
                STATUS_FAILED,
                booking.getBookingStatus(), // PENDING_PAYMENT
                "SIMULATED",
                "Simulated payment failed: " + failureReason,
                now
        );
    }

    private SimulatedPaymentSummaryDTO toSimulatedSummary(Booking booking, LocalDateTime holdExpiresAt) {
        Journey journey = booking.getJourney();
        Train train = journey != null ? journey.getTrain() : null;
        Station fromStation = journey != null ? journey.getFromStation() : null;
        Station toStation = journey != null ? journey.getToStation() : null;
        Coach coach = booking.getCoach();
        Seat seat = booking.getSeat();

        return new SimulatedPaymentSummaryDTO(
                booking.getBookingRef(),
                booking.getBookingId(),
                booking.getPnr(),
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
                coach != null ? coach.getCoachNumber() : null,
                coach != null ? coach.getCoachType() : null,
                seat != null ? seat.getSeatNumber() : null,
                seat != null ? seat.getSeatType() : null,
                booking.getAmount(),
                booking.getBookingStatus(),
                booking.getPaymentStatus(),
                holdExpiresAt,
                "SIMULATED"
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
