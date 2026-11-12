package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.config.FareProperties;
import in.yograj.learnspringboot.railway_booking.dto.*;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.SeatHoldExpiredException;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.outbox.OutboxEventService;
import in.yograj.learnspringboot.railway_booking.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:stage5-services-tests;DB_CLOSE_DELAY=-1",
        "DB_USERNAME=sa",
        "DB_PASSWORD=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:stage5-services-tests;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length",
        "railway.seat-hold-reaper.enabled=false"
})
@Transactional
class Stage5CoreServicesIntegrationTests {

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private SeatInventoryService seatInventoryService;

    @Autowired
    private FareCalculationService fareCalculationService;

    @Autowired
    private DistanceResolverService distanceResolverService;

    @Autowired
    private FareProperties fareProperties;

    @Autowired
    private SeatHoldReaperService seatHoldReaperService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StationRepository stationRepository;

    @Autowired
    private TrainRepository trainRepository;

    @Autowired
    private TrainRouteRepository trainRouteRepository;

    @Autowired
    private CoachRepository coachRepository;

    @Autowired
    private SeatRepository seatRepository;

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
    private PassengerRepository passengerRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private OutboxEventService outboxEventService;

    private User primaryUser;
    private User secondUser;
    private Station hwhStation;
    private Station ndlsStation;
    private Station unknownStation;
    private Train train;
    private TrainRoute route;
    private Journey journey;
    private JourneyStop hwhStop;
    private JourneyStop ndlsStop;
    private Coach coach3A;
    private Coach coachSL;
    private Seat seat3A_1;
    private Seat seat3A_2;
    private Seat seat3A_3;
    private Seat seatSL_1;
    private JourneySeatInventory inv3A_1;
    private JourneySeatInventory inv3A_2;
    private JourneySeatInventory inv3A_3;
    private JourneySeatInventory invSL_1;

    @BeforeEach
    void setUp() {
        primaryUser = new User();
        primaryUser.setUsername("stage5_traveler_" + System.nanoTime());
        primaryUser.setPassword("$2a$10$dummyHashValueForTestingOnly12345678901234567890");
        primaryUser.setRole("ROLE_USER");
        primaryUser.setFullName("Aarav Mehta");
        primaryUser.setEmail("aarav_" + System.nanoTime() + "@example.com");
        primaryUser.setPhoneNumber("9876543210");
        primaryUser = userRepository.save(primaryUser);

        secondUser = new User();
        secondUser.setUsername("stage5_other_" + System.nanoTime());
        secondUser.setPassword("$2a$10$dummyHashValueForTestingOnly12345678901234567890");
        secondUser.setRole("ROLE_USER");
        secondUser.setFullName("Second User");
        secondUser.setEmail("second_" + System.nanoTime() + "@example.com");
        secondUser.setPhoneNumber("9876543211");
        secondUser = userRepository.save(secondUser);

        hwhStation = new Station();
        hwhStation.setStationCode("HWH");
        hwhStation.setStationName("Howrah Junction");
        hwhStation.setCity("Kolkata");
        hwhStation.setState("West Bengal");
        hwhStation = stationRepository.save(hwhStation);

        ndlsStation = new Station();
        ndlsStation.setStationCode("NDLS");
        ndlsStation.setStationName("New Delhi");
        ndlsStation.setCity("New Delhi");
        ndlsStation.setState("Delhi");
        ndlsStation = stationRepository.save(ndlsStation);

        unknownStation = new Station();
        unknownStation.setStationCode("XYZ_UNKNOWN");
        unknownStation.setStationName("Unknown Station");
        unknownStation.setCity("Nowhere");
        unknownStation.setState("Nowhere");
        unknownStation = stationRepository.save(unknownStation);

        train = new Train();
        train.setTrainNumber("12301");
        train.setTrainName("Howrah Rajdhani");
        train = trainRepository.save(train);

        route = new TrainRoute();
        route.setTrain(train);
        route.setRouteName("HWH-NDLS");
        route = trainRouteRepository.save(route);

        journey = new Journey();
        journey.setTrain(train);
        journey.setTrainRoute(route);
        journey.setFromStation(hwhStation);
        journey.setToStation(ndlsStation);
        journey.setJourneyDate(LocalDate.now().plusDays(7));
        journey.setDepartureTime(LocalTime.of(16, 50));
        journey.setArrivalTime(LocalTime.of(10, 0));
        journey = journeyRepository.save(journey);

        hwhStop = new JourneyStop();
        hwhStop.setJourney(journey);
        hwhStop.setStation(hwhStation);
        hwhStop.setStopSequence(1);
        hwhStop.setDistanceFromOriginKm(BigDecimal.ZERO);
        hwhStop = journeyStopRepository.save(hwhStop);

        ndlsStop = new JourneyStop();
        ndlsStop.setJourney(journey);
        ndlsStop.setStation(ndlsStation);
        ndlsStop.setStopSequence(2);
        ndlsStop.setDistanceFromOriginKm(new BigDecimal("1447.00"));
        ndlsStop = journeyStopRepository.save(ndlsStop);

        coach3A = new Coach();
        coach3A.setTrain(train);
        coach3A.setCoachNumber("B1");
        coach3A.setCoachType("3A");
        coach3A.setSeatCapacity(6);
        coach3A = coachRepository.save(coach3A);

        coachSL = new Coach();
        coachSL.setTrain(train);
        coachSL.setCoachNumber("S1");
        coachSL.setCoachType("SL");
        coachSL.setSeatCapacity(6);
        coachSL = coachRepository.save(coachSL);

        seat3A_1 = new Seat();
        seat3A_1.setCoach(coach3A);
        seat3A_1.setSeatNumber(1);
        seat3A_1.setSeatType("LOWER");
        seat3A_1 = seatRepository.save(seat3A_1);

        seat3A_2 = new Seat();
        seat3A_2.setCoach(coach3A);
        seat3A_2.setSeatNumber(2);
        seat3A_2.setSeatType("UPPER");
        seat3A_2 = seatRepository.save(seat3A_2);

        seat3A_3 = new Seat();
        seat3A_3.setCoach(coach3A);
        seat3A_3.setSeatNumber(3);
        seat3A_3.setSeatType("SIDE_LOWER");
        seat3A_3 = seatRepository.save(seat3A_3);

        seatSL_1 = new Seat();
        seatSL_1.setCoach(coachSL);
        seatSL_1.setSeatNumber(1);
        seatSL_1.setSeatType("LOWER");
        seatSL_1 = seatRepository.save(seatSL_1);

        inv3A_1 = new JourneySeatInventory();
        inv3A_1.setJourney(journey);
        inv3A_1.setCoach(coach3A);
        inv3A_1.setSeat(seat3A_1);
        inv3A_1.setStatus("AVAILABLE");
        inv3A_1 = inventoryRepository.save(inv3A_1);

        inv3A_2 = new JourneySeatInventory();
        inv3A_2.setJourney(journey);
        inv3A_2.setCoach(coach3A);
        inv3A_2.setSeat(seat3A_2);
        inv3A_2.setStatus("AVAILABLE");
        inv3A_2 = inventoryRepository.save(inv3A_2);

        inv3A_3 = new JourneySeatInventory();
        inv3A_3.setJourney(journey);
        inv3A_3.setCoach(coach3A);
        inv3A_3.setSeat(seat3A_3);
        inv3A_3.setStatus("AVAILABLE");
        inv3A_3 = inventoryRepository.save(inv3A_3);

        invSL_1 = new JourneySeatInventory();
        invSL_1.setJourney(journey);
        invSL_1.setCoach(coachSL);
        invSL_1.setSeat(seatSL_1);
        invSL_1.setStatus("AVAILABLE");
        invSL_1 = inventoryRepository.save(invSL_1);
    }

    // =========================================================================
    // 1. DYNAMIC FARE ENGINE & DISTANCE RESOLVER
    // =========================================================================

    @Test
    @DisplayName("DistanceResolver resolves 1447 km corridor and rejects unknown corridors")
    void distanceResolutionVerification() {
        // Known corridor via stops
        DistanceResolverService.ResolvedDistance res1 = distanceResolverService.resolveDistance(
                journey, hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId());
        assertThat(res1.distanceKm()).isEqualTo(1447);

        // Unknown corridor rejection (no 500 km fallback!)
        Journey unknownJourney = new Journey();
        unknownJourney.setTrain(train);
        unknownJourney.setTrainRoute(route);
        unknownJourney.setFromStation(unknownStation);
        unknownJourney.setToStation(ndlsStation);
        unknownJourney.setJourneyDate(LocalDate.now().plusDays(2));
        unknownJourney.setDepartureTime(LocalTime.of(8, 0));
        unknownJourney.setArrivalTime(LocalTime.of(12, 0));
        unknownJourney = journeyRepository.save(unknownJourney);

        Journey finalUnknown = unknownJourney;
        assertThatThrownBy(() -> distanceResolverService.resolveDistance(finalUnknown, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("No reliable route distance data available");
    }

    @Test
    @DisplayName("FareCalculationService calculates dynamic breakdown for single and multi-passenger quotes")
    void dynamicFareCalculationVerification() {
        // Single passenger 3A quote
        FareQuoteResponseDTO quote1 = fareCalculationService.calculateFareQuote(
                journey.getJourneyId(), "3A", 1, hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId());

        assertThat(quote1.distanceKm()).isEqualTo(1447);
        assertThat(quote1.travelClass()).isEqualTo("3A");
        assertThat(quote1.passengerCount()).isEqualTo(1);
        assertThat(quote1.perPassengerFare().baseFare()).isGreaterThan(BigDecimal.ZERO);
        assertThat(quote1.bookingTotal()).isEqualTo(quote1.perPassengerFare().total());

        // Multi-passenger (3 passengers) 3A quote
        FareQuoteResponseDTO quote3 = fareCalculationService.calculateFareQuote(
                journey.getJourneyId(), "3A", 3, hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId());

        assertThat(quote3.passengerCount()).isEqualTo(3);
        assertThat(quote3.bookingTotal()).isEqualByComparingTo(
                quote1.perPassengerFare().total().multiply(BigDecimal.valueOf(3)));
    }

    // =========================================================================
    // 2. SINGLE-PASSENGER BOOKING BACKWARD COMPATIBILITY
    // =========================================================================

    @Test
    @DisplayName("Single passenger booking without passengers list falls back to seatId and computes authoritative fare")
    void singlePassengerBookingLifecycle() {
        // Hold seat
        SeatAvailabilityResponseDTO hold = seatInventoryService.holdSeat(
                journey.getJourneyId(), seat3A_1.getSeatId(), 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(), primaryUser.getUsername());
        assertThat(hold.status()).isEqualTo("HOLD");

        // Create booking with legacy seatId (null passengers)
        CreateBookingRequestDTO req = new CreateBookingRequestDTO(
                journey.getJourneyId(), seat3A_1.getSeatId(),
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(), BookingMode.GENERAL);

        BookingResponseDTO bookingResponse = bookingService.createBooking(req, primaryUser.getUsername());
        assertThat(bookingResponse.bookingRef()).isNotBlank();
        assertThat(bookingResponse.pnr()).matches("^\\d{10}$");
        assertThat(bookingResponse.bookingStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(bookingResponse.amount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(bookingResponse.passengers()).hasSize(1);
        assertThat(bookingResponse.passengers().get(0).fullName()).isEqualTo("Aarav Mehta");

        // Pay for booking
        PaymentRequestDTO payReq = new PaymentRequestDTO("MOCK_UPI", "IDEMP-" + System.nanoTime(), false);
        PaymentResponseDTO payResp = bookingService.processPayment(
                bookingResponse.bookingRef(), payReq, primaryUser.getUsername());

        assertThat(payResp.paymentStatus()).isEqualTo("SUCCESS");
        assertThat(payResp.bookingStatus()).isEqualTo("CONFIRMED");
        assertThat(payResp.amount()).isEqualByComparingTo(bookingResponse.amount());

        // Verify booking detail response and PNR query
        BookingDetailResponseDTO detail = bookingService.getBookingByPnr(bookingResponse.pnr(), primaryUser.getUsername());
        assertThat(detail.bookingRef()).isEqualTo(bookingResponse.bookingRef());
        assertThat(detail.passengers()).hasSize(1);
        assertThat(detail.distanceKm()).isEqualTo(1447);
        assertThat(detail.fareBreakdown()).isNotNull();
    }

    // =========================================================================
    // 3. MULTI-PASSENGER BOOKING & BATCH HOLD
    // =========================================================================

    @Test
    @DisplayName("Multi-passenger booking batch-holds seats, calculates aggregate fare, and confirms all passengers")
    void multiPassengerBookingLifecycle() {
        // Batch hold 2 seats
        List<Long> seatIds = List.of(seat3A_2.getSeatId(), seat3A_1.getSeatId()); // Unsorted to test deterministic locking
        List<SeatAvailabilityResponseDTO> batchHolds = seatInventoryService.holdSeatsBatch(
                journey.getJourneyId(), seatIds, 10,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                "3A", "B1", primaryUser.getUsername());

        assertThat(batchHolds).hasSize(2);
        assertThat(batchHolds).allMatch(h -> "HOLD".equalsIgnoreCase(h.status()));

        // Create booking with 2 passengers
        List<PassengerRequestDTO> passengers = List.of(
                new PassengerRequestDTO("Aarav Mehta", 32, "MALE", seat3A_1.getSeatId(), "LOWER"),
                new PassengerRequestDTO("Priya Mehta", 29, "FEMALE", seat3A_2.getSeatId(), "UPPER")
        );
        CreateBookingRequestDTO bookingReq = new CreateBookingRequestDTO(
                journey.getJourneyId(), null,
                hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                BookingMode.GENERAL, "3A", "B1", passengers);

        BookingResponseDTO bookingResponse = bookingService.createBooking(bookingReq, primaryUser.getUsername());
        assertThat(bookingResponse.pnr()).matches("^\\d{10}$");
        assertThat(bookingResponse.passengers()).hasSize(2);

        // Verify quote aggregate: total is 2x single
        FareQuoteResponseDTO singleQuote = fareCalculationService.calculateFareQuote(
                journey.getJourneyId(), "3A", 1, hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId());
        BigDecimal expectedTotal = singleQuote.bookingTotal().multiply(BigDecimal.valueOf(2));
        assertThat(bookingResponse.amount()).isEqualByComparingTo(expectedTotal);

        // Verify PassengerRepository persistence
        List<Passenger> savedPassengers = passengerRepository.findByBooking_BookingId(bookingResponse.bookingId());
        assertThat(savedPassengers).hasSize(2);

        // Process payment
        String idempKey = "IDEMP-MULTI-" + System.nanoTime();
        PaymentRequestDTO payReq = new PaymentRequestDTO("MOCK_UPI", idempKey, false);
        PaymentResponseDTO payResp = bookingService.processPayment(bookingResponse.bookingRef(), payReq, primaryUser.getUsername());

        assertThat(payResp.paymentStatus()).isEqualTo("SUCCESS");
        assertThat(payResp.bookingStatus()).isEqualTo("CONFIRMED");

        // Verify both passenger seat allocations transitioned to BOOKED
        for (Passenger p : savedPassengers) {
            SeatAllocation alloc = allocationRepository.findById(p.getSeatAllocation().getSeatAllocationId()).orElseThrow();
            assertThat(alloc.getStatus()).isEqualTo("BOOKED");
        }

        // Verify Idempotent payment replay
        PaymentResponseDTO replayResp = bookingService.processPayment(bookingResponse.bookingRef(), payReq, primaryUser.getUsername());
        assertThat(replayResp.transactionRef()).isEqualTo(payResp.transactionRef());
        assertThat(replayResp.paymentStatus()).isEqualTo("SUCCESS");

        // Verify Cancellation releases all passenger allocations
        BookingDetailResponseDTO cancelResp = bookingService.cancelBooking(bookingResponse.bookingRef(), primaryUser.getUsername());
        assertThat(cancelResp.bookingStatus()).isEqualTo("CANCELLED");
        for (Passenger p : savedPassengers) {
            SeatAllocation alloc = allocationRepository.findById(p.getSeatAllocation().getSeatAllocationId()).orElseThrow();
            assertThat(alloc.getStatus()).isEqualTo("CANCELLED");
        }
    }

    // =========================================================================
    // 4. VALIDATION & ERROR HANDLING
    // =========================================================================

    @Test
    @DisplayName("Booking rejects requests exceeding 6 passengers or containing duplicate seats")
    void passengerValidationEnforcement() {
        // Exceeding 6 passengers
        List<PassengerRequestDTO> sevenPassengers = List.of(
                new PassengerRequestDTO("P1", 20, "MALE", 1L, null),
                new PassengerRequestDTO("P2", 21, "MALE", 2L, null),
                new PassengerRequestDTO("P3", 22, "MALE", 3L, null),
                new PassengerRequestDTO("P4", 23, "MALE", 4L, null),
                new PassengerRequestDTO("P5", 24, "MALE", 5L, null),
                new PassengerRequestDTO("P6", 25, "MALE", 6L, null),
                new PassengerRequestDTO("P7", 26, "MALE", 7L, null)
        );
        CreateBookingRequestDTO reqTooMany = new CreateBookingRequestDTO(
                journey.getJourneyId(), null, hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                BookingMode.GENERAL, "3A", null, sevenPassengers);

        assertThatThrownBy(() -> bookingService.createBooking(reqTooMany, primaryUser.getUsername()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("A maximum of 6 passengers are allowed");

        // Duplicate seats in passenger list
        List<PassengerRequestDTO> dupSeats = List.of(
                new PassengerRequestDTO("P1", 20, "MALE", seat3A_1.getSeatId(), null),
                new PassengerRequestDTO("P2", 21, "FEMALE", seat3A_1.getSeatId(), null)
        );
        CreateBookingRequestDTO reqDup = new CreateBookingRequestDTO(
                journey.getJourneyId(), null, hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId(),
                BookingMode.GENERAL, "3A", null, dupSeats);

        assertThatThrownBy(() -> bookingService.createBooking(reqDup, primaryUser.getUsername()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Duplicate seat ID");
    }

    // =========================================================================
    // 5. CLASS-AWARE AVAILABILITY HIERARCHY
    // =========================================================================

    @Test
    @DisplayName("SeatInventoryService returns class-level aggregated availability and fare quote")
    void classLevelAvailabilityVerification() {
        List<TravelClassAvailabilityDTO> classes = seatInventoryService.getJourneyClassesAvailability(
                journey.getJourneyId(), hwhStop.getJourneyStopId(), ndlsStop.getJourneyStopId());

        assertThat(classes).isNotEmpty();
        TravelClassAvailabilityDTO class3A = classes.stream()
                .filter(c -> "3A".equalsIgnoreCase(c.classCode()))
                .findFirst()
                .orElse(null);

        assertThat(class3A).isNotNull();
        assertThat(class3A.className()).isEqualTo("AC 3 Tier");
        assertThat(class3A.totalSeats()).isEqualTo(3);
        assertThat(class3A.availableSeats()).isEqualTo(3);
        assertThat(class3A.baseFare()).isGreaterThan(BigDecimal.ZERO);
        assertThat(class3A.coaches()).hasSize(1);
        assertThat(class3A.coaches().get(0).coachNumber()).isEqualTo("B1");
    }
}
