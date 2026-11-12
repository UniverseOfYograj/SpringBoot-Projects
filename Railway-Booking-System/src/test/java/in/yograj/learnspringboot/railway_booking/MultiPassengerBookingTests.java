package in.yograj.learnspringboot.railway_booking;

import in.yograj.learnspringboot.railway_booking.dto.*;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.exception.SeatHoldExpiredException;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.*;
import in.yograj.learnspringboot.railway_booking.service.BookingService;
import in.yograj.learnspringboot.railway_booking.service.SeatInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:multi-passenger-booking-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length",
        "railway.seat-hold-reaper.enabled=false"
})
class MultiPassengerBookingTests {

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    @Autowired
    private SeatInventoryService seatInventoryService;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TrainRepository trainRepository;

    @Autowired
    private StationRepository stationRepository;

    @Autowired
    private CoachRepository coachRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private TrainRouteRepository trainRouteRepository;

    @Autowired
    private TrainStopRepository trainStopRepository;

    @Autowired
    private JourneyRepository journeyRepository;

    @Autowired
    private JourneyStopRepository journeyStopRepository;

    @Autowired
    private JourneySeatInventoryRepository inventoryRepository;

    @Autowired
    private SeatAllocationRepository allocationRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private PassengerRepository passengerRepository;

    private User primaryUser;
    private User secondUser;
    private Journey testJourney;
    private JourneyStop hwhStop;
    private JourneyStop ndlsStop;
    private Coach b1Coach;
    private Coach s1Coach;
    private Seat seatB1_1;
    private Seat seatB1_2;
    private Seat seatB1_3;
    private Seat seatS1_1;

    @BeforeEach
    void setUp() {
        paymentTransactionRepository.deleteAll();
        passengerRepository.deleteAll();
        bookingRepository.deleteAll();
        allocationRepository.deleteAll();
        inventoryRepository.deleteAll();
        journeyStopRepository.deleteAll();
        journeyRepository.deleteAll();
        trainStopRepository.deleteAll();
        trainRouteRepository.deleteAll();
        seatRepository.deleteAll();
        coachRepository.deleteAll();
        trainRepository.deleteAll();
        stationRepository.deleteAll();
        userRepository.deleteAll();

        primaryUser = new User();
        primaryUser.setUsername("primary_traveler");
        primaryUser.setEmail("aarav@example.com");
        primaryUser.setPassword("hashed_password");
        primaryUser.setFullName("Aarav Sharma");
        primaryUser.setRole("ROLE_USER");
        primaryUser = userRepository.save(primaryUser);

        secondUser = new User();
        secondUser.setUsername("secondary_traveler");
        secondUser.setEmail("rohan@example.com");
        secondUser.setPassword("hashed_password");
        secondUser.setFullName("Rohan Gupta");
        secondUser.setRole("ROLE_USER");
        secondUser = userRepository.save(secondUser);

        Station hwh = new Station();
        hwh.setStationCode("HWH");
        hwh.setStationName("Howrah Junction");
        hwh = stationRepository.save(hwh);

        Station ndls = new Station();
        ndls.setStationCode("NDLS");
        ndls.setStationName("New Delhi");
        ndls = stationRepository.save(ndls);

        Train train = new Train();
        train.setTrainNumber("12301");
        train.setTrainName("Howrah Rajdhani Express");
        train = trainRepository.save(train);

        b1Coach = new Coach();
        b1Coach.setTrain(train);
        b1Coach.setCoachNumber("B1");
        b1Coach.setCoachType("3A");
        b1Coach.setSeatCapacity(6);
        b1Coach = coachRepository.save(b1Coach);

        s1Coach = new Coach();
        s1Coach.setTrain(train);
        s1Coach.setCoachNumber("S1");
        s1Coach.setCoachType("SL");
        s1Coach.setSeatCapacity(6);
        s1Coach = coachRepository.save(s1Coach);

        seatB1_1 = new Seat();
        seatB1_1.setCoach(b1Coach);
        seatB1_1.setSeatNumber(7);
        seatB1_1.setSeatType("LOWER");
        seatB1_1 = seatRepository.save(seatB1_1);

        seatB1_2 = new Seat();
        seatB1_2.setCoach(b1Coach);
        seatB1_2.setSeatNumber(8);
        seatB1_2.setSeatType("UPPER");
        seatB1_2 = seatRepository.save(seatB1_2);

        seatB1_3 = new Seat();
        seatB1_3.setCoach(b1Coach);
        seatB1_3.setSeatNumber(9);
        seatB1_3.setSeatType("SIDE LOWER");
        seatB1_3 = seatRepository.save(seatB1_3);

        seatS1_1 = new Seat();
        seatS1_1.setCoach(s1Coach);
        seatS1_1.setSeatNumber(1);
        seatS1_1.setSeatType("LOWER");
        seatS1_1 = seatRepository.save(seatS1_1);

        TrainRoute route = new TrainRoute();
        route.setTrain(train);
        route.setRouteName("HWH-NDLS Corridor");
        route = trainRouteRepository.save(route);

        TrainStop ts1 = new TrainStop();
        ts1.setTrainRoute(route);
        ts1.setStation(hwh);
        ts1.setStopSequence(1);
        ts1.setDepartureTime(LocalTime.of(16, 55));
        ts1.setDayOffset(0);
        trainStopRepository.save(ts1);

        TrainStop ts2 = new TrainStop();
        ts2.setTrainRoute(route);
        ts2.setStation(ndls);
        ts2.setStopSequence(2);
        ts2.setArrivalTime(LocalTime.of(10, 5));
        ts2.setDayOffset(1);
        trainStopRepository.save(ts2);

        testJourney = new Journey();
        testJourney.setTrain(train);
        testJourney.setTrainRoute(route);
        testJourney.setFromStation(hwh);
        testJourney.setToStation(ndls);
        testJourney.setJourneyDate(LocalDate.of(2026, 12, 1));
        testJourney.setDepartureTime(LocalTime.of(16, 55));
        testJourney.setArrivalTime(LocalTime.of(10, 5));
        testJourney = journeyRepository.save(testJourney);

        hwhStop = new JourneyStop();
        hwhStop.setJourney(testJourney);
        hwhStop.setStation(hwh);
        hwhStop.setStopSequence(1);
        hwhStop.setDepartureAt(LocalDateTime.of(2026, 12, 1, 16, 55));
        hwhStop = journeyStopRepository.save(hwhStop);

        ndlsStop = new JourneyStop();
        ndlsStop.setJourney(testJourney);
        ndlsStop.setStation(ndls);
        ndlsStop.setStopSequence(2);
        ndlsStop.setArrivalAt(LocalDateTime.of(2026, 12, 2, 10, 5));
        ndlsStop = journeyStopRepository.save(ndlsStop);

        createInventory(seatB1_1);
        createInventory(seatB1_2);
        createInventory(seatB1_3);
        createInventory(seatS1_1);
    }

    private JourneySeatInventory createInventory(Seat seat) {
        JourneySeatInventory inv = new JourneySeatInventory();
        inv.setJourney(testJourney);
        inv.setCoach(seat.getCoach());
        inv.setSeat(seat);
        inv.setStatus("AVAILABLE");
        return inventoryRepository.save(inv);
    }

    @Test
    void multiPassengerBooking_withMultipleSeatsAndPassengers_succeeds() {
        // Hold 2 seats
        seatInventoryService.holdSeat(
                testJourney.getJourneyId(), seatB1_1.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", primaryUser.getUsername());

        seatInventoryService.holdSeat(
                testJourney.getJourneyId(), seatB1_2.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", primaryUser.getUsername());

        List<PassengerRequestDTO> passengers = List.of(
                new PassengerRequestDTO("Aarav Sharma", 29, "MALE", seatB1_1.getSeatId(), "LOWER"),
                new PassengerRequestDTO("Pooja Sharma", 27, "FEMALE", seatB1_2.getSeatId(), "UPPER")
        );

        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                testJourney.getJourneyId(), null,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", passengers
        );

        BookingResponseDTO bookingResponse = bookingService.createBooking(request, primaryUser.getUsername());

        assertNotNull(bookingResponse);
        assertNotNull(bookingResponse.bookingRef());
        assertEquals("PENDING_PAYMENT", bookingResponse.bookingStatus());
        assertEquals("PENDING", bookingResponse.paymentStatus());
        // Dynamic server-calculated fare for 3A over HWH-NDLS (1,447 km) is 1836.50 * 2 = 3673.00
        assertEquals(new BigDecimal("3673.00"), bookingResponse.amount());
        assertNotNull(bookingResponse.fareBreakdown());
        assertEquals(1447, bookingResponse.distanceKm());
        assertEquals(2, bookingResponse.passengers().size());

        // Verify individual passenger mappings
        PassengerResponseDTO p1 = bookingResponse.passengers().stream()
                .filter(p -> p.seatNumber() == 7).findFirst().orElseThrow();
        assertEquals("Aarav Sharma", p1.fullName());
        assertEquals(29, p1.age());
        assertEquals("MALE", p1.gender());
        assertEquals("3A", p1.coachType());

        PassengerResponseDTO p2 = bookingResponse.passengers().stream()
                .filter(p -> p.seatNumber() == 8).findFirst().orElseThrow();
        assertEquals("Pooja Sharma", p2.fullName());
        assertEquals(27, p2.age());
        assertEquals("FEMALE", p2.gender());
    }

    @Test
    void singlePassengerBooking_synthesizesPassengerFromLegacySeatId_succeeds() {
        // Hold 1 seat
        seatInventoryService.holdSeat(
                testJourney.getJourneyId(), seatB1_1.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", primaryUser.getUsername());

        // Legacy 6-parameter request without passengers list
        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                testJourney.getJourneyId(), seatB1_1.getSeatId(),
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1"
        );

        BookingResponseDTO bookingResponse = bookingService.createBooking(request, primaryUser.getUsername());
        assertNotNull(bookingResponse);
        assertEquals(1, bookingResponse.passengers().size());
        assertEquals("Aarav Sharma", bookingResponse.passengers().get(0).fullName());
        assertEquals(7, bookingResponse.passengers().get(0).seatNumber());
    }

    @Test
    void multiPassengerBooking_withDuplicateSeats_rejectedWithBadRequestException() {
        // Both passengers specify the exact same seatId
        List<PassengerRequestDTO> duplicateSeatPassengers = List.of(
                new PassengerRequestDTO("Aarav Sharma", 29, "MALE", seatB1_1.getSeatId(), "LOWER"),
                new PassengerRequestDTO("Pooja Sharma", 27, "FEMALE", seatB1_1.getSeatId(), "UPPER")
        );

        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                testJourney.getJourneyId(), null,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", duplicateSeatPassengers
        );

        BadRequestException ex = assertThrows(
                BadRequestException.class,
                () -> bookingService.createBooking(request, primaryUser.getUsername())
        );
        assertTrue(ex.getMessage().contains("Duplicate seat"));
    }

    @Test
    void multiPassengerBooking_withInvalidPassengerDetails_rejected() {
        // Empty name
        List<PassengerRequestDTO> invalidPassengers = List.of(
                new PassengerRequestDTO("", 29, "MALE", seatB1_1.getSeatId(), "LOWER")
        );
        CreateBookingRequestDTO req1 = new CreateBookingRequestDTO(
                testJourney.getJourneyId(), null,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", invalidPassengers
        );
        assertThrows(BadRequestException.class, () -> bookingService.createBooking(req1, primaryUser.getUsername()));

        // Age out of range (> 125)
        List<PassengerRequestDTO> invalidAge = List.of(
                new PassengerRequestDTO("Aarav Sharma", 150, "MALE", seatB1_1.getSeatId(), "LOWER")
        );
        CreateBookingRequestDTO req2 = new CreateBookingRequestDTO(
                testJourney.getJourneyId(), null,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", invalidAge
        );
        assertThrows(BadRequestException.class, () -> bookingService.createBooking(req2, primaryUser.getUsername()));
    }

    @Test
    void multiPassengerBooking_withExpiredHold_failsAtomicallyWithoutPartialBooking() {
        // Hold seat 1 normally
        seatInventoryService.holdSeat(
                testJourney.getJourneyId(), seatB1_1.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", primaryUser.getUsername());

        // Seat 2 is not held or expired
        List<PassengerRequestDTO> passengers = List.of(
                new PassengerRequestDTO("Aarav Sharma", 29, "MALE", seatB1_1.getSeatId(), "LOWER"),
                new PassengerRequestDTO("Pooja Sharma", 27, "FEMALE", seatB1_2.getSeatId(), "UPPER")
        );

        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                testJourney.getJourneyId(), null,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", passengers
        );

        assertThrows(BadRequestException.class, () -> bookingService.createBooking(request, primaryUser.getUsername()));

        // Verify zero bookings created in the database (atomic fail-closed)
        assertEquals(0, bookingRepository.count());
        assertEquals(0, passengerRepository.count());
    }

    @Test
    void multiPassengerBooking_withCrossUserHold_rejectedWithAccessDeniedException() {
        // Seat 1 held by primaryUser
        seatInventoryService.holdSeat(
                testJourney.getJourneyId(), seatB1_1.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", primaryUser.getUsername());

        // Seat 2 held by secondUser
        seatInventoryService.holdSeat(
                testJourney.getJourneyId(), seatB1_2.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", secondUser.getUsername());

        List<PassengerRequestDTO> passengers = List.of(
                new PassengerRequestDTO("Aarav Sharma", 29, "MALE", seatB1_1.getSeatId(), "LOWER"),
                new PassengerRequestDTO("Rohan Gupta", 28, "MALE", seatB1_2.getSeatId(), "UPPER")
        );

        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                testJourney.getJourneyId(), null,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", passengers
        );

        assertThrows(AccessDeniedException.class, () -> bookingService.createBooking(request, primaryUser.getUsername()));
    }

    @Test
    void multiPassengerBooking_withClassOrCoachMismatch_rejected() {
        // Hold seat in B1 (3A)
        seatInventoryService.holdSeat(
                testJourney.getJourneyId(), seatB1_1.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", primaryUser.getUsername());

        List<PassengerRequestDTO> passengers = List.of(
                new PassengerRequestDTO("Aarav Sharma", 29, "MALE", seatB1_1.getSeatId(), "LOWER")
        );

        // Mismatched travelClass "SL"
        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                testJourney.getJourneyId(), null,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "SL", "B1", passengers
        );

        assertThrows(BadRequestException.class, () -> bookingService.createBooking(request, primaryUser.getUsername()));
    }

    @Test
    void multiPassengerPayment_success_confirmsBookingAndAllPassengerAllocations() {
        seatInventoryService.holdSeat(
                testJourney.getJourneyId(), seatB1_1.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", primaryUser.getUsername());

        seatInventoryService.holdSeat(
                testJourney.getJourneyId(), seatB1_2.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", primaryUser.getUsername());

        List<PassengerRequestDTO> passengers = List.of(
                new PassengerRequestDTO("Aarav Sharma", 29, "MALE", seatB1_1.getSeatId(), "LOWER"),
                new PassengerRequestDTO("Pooja Sharma", 27, "FEMALE", seatB1_2.getSeatId(), "UPPER")
        );

        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                testJourney.getJourneyId(), null,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", passengers
        );

        BookingResponseDTO bookingResponse = bookingService.createBooking(request, primaryUser.getUsername());

        PaymentRequestDTO payReq = new PaymentRequestDTO("MOCK_UPI", "IDEM-MP-001", false);
        PaymentResponseDTO paymentResponse = bookingService.processPayment(
                bookingResponse.bookingRef(), payReq, primaryUser.getUsername());

        assertEquals("SUCCESS", paymentResponse.paymentStatus());
        assertEquals("CONFIRMED", paymentResponse.bookingStatus());
        assertEquals(new BigDecimal("3673.00"), paymentResponse.amount());

        // Verify all passenger allocations are transitioned to BOOKED
        Booking updatedBooking = bookingRepository.findByBookingRef(bookingResponse.bookingRef()).orElseThrow();
        assertEquals("CONFIRMED", updatedBooking.getBookingStatus());
        assertEquals("SUCCESS", updatedBooking.getPaymentStatus());

        for (Passenger p : updatedBooking.getPassengers()) {
            SeatAllocation alloc = allocationRepository.findById(p.getSeatAllocation().getSeatAllocationId()).orElseThrow();
            assertEquals("BOOKED", alloc.getStatus());
        }
    }

    @Test
    void multiPassengerPayment_simulatedFailure_preservesHoldAndAllowsSuccessfulRetry() {
        seatInventoryService.holdSeat(
                testJourney.getJourneyId(), seatB1_1.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", primaryUser.getUsername());

        seatInventoryService.holdSeat(
                testJourney.getJourneyId(), seatB1_2.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", primaryUser.getUsername());

        List<PassengerRequestDTO> passengers = List.of(
                new PassengerRequestDTO("Aarav Sharma", 29, "MALE", seatB1_1.getSeatId(), "LOWER"),
                new PassengerRequestDTO("Pooja Sharma", 27, "FEMALE", seatB1_2.getSeatId(), "UPPER")
        );

        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                testJourney.getJourneyId(), null,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", passengers
        );

        BookingResponseDTO bookingResponse = bookingService.createBooking(request, primaryUser.getUsername());

        // 1. Simulate failure
        PaymentRequestDTO failReq = new PaymentRequestDTO("MOCK_UPI", "IDEM-FAIL-01", true);
        PaymentResponseDTO failRes = bookingService.processPayment(
                bookingResponse.bookingRef(), failReq, primaryUser.getUsername());

        assertEquals("FAILED", failRes.paymentStatus());
        assertEquals("PENDING_PAYMENT", failRes.bookingStatus());

        // Verify allocations are still HOLD
        Booking pendingBooking = bookingRepository.findByBookingRef(bookingResponse.bookingRef()).orElseThrow();
        for (Passenger p : pendingBooking.getPassengers()) {
            SeatAllocation alloc = allocationRepository.findById(p.getSeatAllocation().getSeatAllocationId()).orElseThrow();
            assertEquals("HOLD", alloc.getStatus());
        }

        // 2. Retry with success
        PaymentRequestDTO retryReq = new PaymentRequestDTO("MOCK_UPI", "IDEM-SUCCESS-02", false);
        PaymentResponseDTO retryRes = bookingService.processPayment(
                bookingResponse.bookingRef(), retryReq, primaryUser.getUsername());

        assertEquals("SUCCESS", retryRes.paymentStatus());
        assertEquals("CONFIRMED", retryRes.bookingStatus());
    }

    @Test
    void getBookingHistory_withMultiplePassengers_returnsAllPassengerRosters() {
        seatInventoryService.holdSeat(
                testJourney.getJourneyId(), seatB1_1.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", primaryUser.getUsername());

        seatInventoryService.holdSeat(
                testJourney.getJourneyId(), seatB1_2.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", primaryUser.getUsername());

        List<PassengerRequestDTO> passengers = List.of(
                new PassengerRequestDTO("Aarav Sharma", 29, "MALE", seatB1_1.getSeatId(), "LOWER"),
                new PassengerRequestDTO("Pooja Sharma", 27, "FEMALE", seatB1_2.getSeatId(), "UPPER")
        );

        CreateBookingRequestDTO request = new CreateBookingRequestDTO(
                testJourney.getJourneyId(), null,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", passengers
        );

        BookingResponseDTO booking = bookingService.createBooking(request, primaryUser.getUsername());

        PageResponseDTO<BookingDetailResponseDTO> history = bookingService.getBookingHistory(0, 10, primaryUser.getUsername());
        assertNotNull(history);
        assertEquals(1, history.totalElements());
        BookingDetailResponseDTO detail = history.content().get(0);
        assertEquals(booking.bookingRef(), detail.bookingRef());
        assertEquals(2, detail.passengers().size());
    }

    @Test
    void batchHoldSeats_holdsMultipleSeatsAtomically() {
        List<Long> seatIds = List.of(seatB1_1.getSeatId(), seatB1_2.getSeatId());
        List<SeatAvailabilityResponseDTO> heldSeats = seatInventoryService.holdSeats(
                testJourney.getJourneyId(), seatIds, 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", primaryUser.getUsername()
        );

        assertEquals(2, heldSeats.size());
        assertTrue(heldSeats.stream().allMatch(s -> "HOLD".equalsIgnoreCase(s.status())));
    }
}
