package in.yograj.learnspringboot.railway_booking;

import in.yograj.learnspringboot.railway_booking.dto.BookingDetailResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.BookingResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.CreateBookingRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.CreateTrainRouteRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.JourneyRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PageResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainStopRequestDTO;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.*;
import in.yograj.learnspringboot.railway_booking.service.BookingService;
import in.yograj.learnspringboot.railway_booking.service.JourneyService;
import in.yograj.learnspringboot.railway_booking.service.SeatInventoryService;
import in.yograj.learnspringboot.railway_booking.service.TrainRouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:booking-query-lifecycle-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length",
        "railway.seat-hold-reaper.enabled=false"
})
class BookingQueryLifecycleTests {

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    @Autowired private TrainRepository trainRepository;
    @Autowired private StationRepository stationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JourneySeatInventoryRepository inventoryRepository;
    @Autowired private JourneyStopRepository journeyStopRepository;
    @Autowired private SeatAllocationRepository allocationRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private PaymentTransactionRepository paymentTransactionRepository;
    @Autowired private TrainRouteService trainRouteService;
    @Autowired private JourneyService journeyService;
    @Autowired private SeatInventoryService seatInventoryService;
    @Autowired private BookingService bookingService;

    private Long journeyId;
    private List<JourneyStop> runStops;
    private List<JourneySeatInventory> inventories;

    @BeforeEach
    void setup() {
        paymentTransactionRepository.deleteAll();
        bookingRepository.deleteAll();
        allocationRepository.deleteAll();

        Train train = new Train();
        train.setTrainNumber("QRY-" + UUID.randomUUID().toString().substring(0, 8));
        train.setTrainName("Query Express");
        train.setStatus("ACTIVE");
        train = trainRepository.save(train);

        List<Station> stations = List.of(
                station("QST1-" + UUID.randomUUID().toString().substring(0, 4), "Query Station One"),
                station("QST2-" + UUID.randomUUID().toString().substring(0, 4), "Query Station Two"),
                station("QST3-" + UUID.randomUUID().toString().substring(0, 4), "Query Station Three")
        );
        stationRepository.saveAll(stations);

        var route = trainRouteService.createRoute(train.getTrainId(),
                new CreateTrainRouteRequestDTO("route-query-" + UUID.randomUUID(),
                        List.of(
                                new TrainStopRequestDTO(stations.get(0).getStationId(), 1, null, LocalTime.of(8, 0), 0),
                                new TrainStopRequestDTO(stations.get(1).getStationId(), 2, LocalTime.of(9, 0), LocalTime.of(9, 10), 0),
                                new TrainStopRequestDTO(stations.get(2).getStationId(), 3, LocalTime.of(10, 30), null, 0)
                        )));

        LocalDate serviceDate = LocalDate.now().plusDays(5);
        var journey = journeyService.createJourney(new JourneyRequestDTO(
                train.getTrainId(), null, null, serviceDate, null, null, route.trainRouteId()));

        this.journeyId = journey.journeyId();
        this.runStops = journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(journeyId);
        this.inventories = inventoryRepository.findByJourney_JourneyId(journeyId);
    }

    private BookingResponseDTO createPendingBookingForSeat(JourneySeatInventory seatInventory, User user, int holdMinutes) {
        seatInventoryService.holdSeat(journeyId, seatInventory.getSeat().getSeatId(), holdMinutes,
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId(), user.getUsername());
        return bookingService.createBooking(new CreateBookingRequestDTO(
                journeyId, seatInventory.getSeat().getSeatId(),
                runStops.get(0).getJourneyStopId(), runStops.get(2).getJourneyStopId()),
                user.getUsername());
    }

    @Test
    void getBookingByRef_ownerRetrievesSuccessfully_allDetailsPopulated() {
        User user = createUser("query-owner", "ROLE_USER");
        BookingResponseDTO created = createPendingBookingForSeat(inventories.get(0), user, 10);

        BookingDetailResponseDTO detail = bookingService.getBookingByRef(created.bookingRef(), user.getUsername());

        assertNotNull(detail);
        assertEquals(created.bookingRef(), detail.bookingRef());
        assertEquals(user.getUserId(), detail.userId());
        assertEquals(user.getUsername(), detail.username());
        assertEquals(journeyId, detail.journeyId());
        assertNotNull(detail.trainNumber());
        assertNotNull(detail.trainName());
        assertNotNull(detail.fromStationCode());
        assertNotNull(detail.fromStationName());
        assertNotNull(detail.toStationCode());
        assertNotNull(detail.toStationName());
        assertNotNull(detail.coachNumber());
        assertNotNull(detail.seatNumber());
        assertEquals("PENDING_PAYMENT", detail.bookingStatus());
        assertEquals("PENDING", detail.paymentStatus());
        assertNotNull(detail.seatAllocationId());
        assertNotNull(detail.createdAt());
    }

    @Test
    void getBookingByRef_adminRetrievesAnotherUsersBooking_succeeds() {
        User regularUser = createUser("query-regular", "ROLE_USER");
        User adminUser = createUser("query-admin", "ROLE_ADMIN");

        BookingResponseDTO created = createPendingBookingForSeat(inventories.get(1), regularUser, 10);

        // Admin accessing regular user's booking
        BookingDetailResponseDTO detail = bookingService.getBookingByRef(created.bookingRef(), adminUser.getUsername());

        assertNotNull(detail);
        assertEquals(created.bookingRef(), detail.bookingRef());
        assertEquals(regularUser.getUserId(), detail.userId());
        assertEquals(regularUser.getUsername(), detail.username());
    }

    @Test
    void getBookingByRef_crossUserAccess_throwsAccessDeniedException() {
        User user1 = createUser("query-user-1", "ROLE_USER");
        User user2 = createUser("query-user-2", "ROLE_USER");

        BookingResponseDTO created = createPendingBookingForSeat(inventories.get(2), user1, 10);

        // User 2 trying to view User 1's booking
        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                bookingService.getBookingByRef(created.bookingRef(), user2.getUsername())
        );

        assertTrue(ex.getMessage().contains("not authorized to view this booking"));
    }

    @Test
    void getBookingByRef_broadAdminSubstringRole_deniesAccess() {
        User owner = createUser("query-owner-broad", "ROLE_USER");
        User pseudoAdmin = createUser("query-pseudo-admin", "ROLE_ADMIN_VIEWER");

        BookingResponseDTO created = createPendingBookingForSeat(inventories.get(2), owner, 10);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                bookingService.getBookingByRef(created.bookingRef(), pseudoAdmin.getUsername())
        );

        assertTrue(ex.getMessage().contains("not authorized to view this booking"));
    }

    @Test
    void getBookingByRef_missingBookingRef_throwsResourceNotFoundException() {
        User user = createUser("query-missing", "ROLE_USER");

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () ->
                bookingService.getBookingByRef("RB-DOES-NOT-EXIST", user.getUsername())
        );

        assertTrue(ex.getMessage().contains("Booking not found for reference"));
    }

    @Test
    void getBookingByRef_blankRef_throwsBadRequestException() {
        User user = createUser("query-blank", "ROLE_USER");

        assertThrows(BadRequestException.class, () ->
                bookingService.getBookingByRef("   ", user.getUsername())
        );
        assertThrows(BadRequestException.class, () ->
                bookingService.getBookingByRef(null, user.getUsername())
        );
    }

    @Test
    void getBookingHistory_emptyForUserWithoutBookings() {
        User user = createUser("query-empty", "ROLE_USER");

        PageResponseDTO<BookingDetailResponseDTO> history = bookingService.getBookingHistory(0, 10, user.getUsername());

        assertNotNull(history);
        assertEquals(0, history.page());
        assertEquals(10, history.size());
        assertEquals(0, history.totalElements());
        assertEquals(0, history.totalPages());
        assertTrue(history.content().isEmpty());
        assertTrue(history.first());
        assertTrue(history.last());
        assertFalse(history.hasNext());
        assertFalse(history.hasPrevious());
    }

    @Test
    void getBookingHistory_regularUserSeesOnlyOwnBookings() {
        User user1 = createUser("history-user-1", "ROLE_USER");
        User user2 = createUser("history-user-2", "ROLE_USER");

        // User 1 has 2 bookings
        BookingResponseDTO b1 = createPendingBookingForSeat(inventories.get(0), user1, 10);
        BookingResponseDTO b2 = createPendingBookingForSeat(inventories.get(1), user1, 10);

        // User 2 has 1 booking
        BookingResponseDTO b3 = createPendingBookingForSeat(inventories.get(2), user2, 10);

        // User 1 queries history
        PageResponseDTO<BookingDetailResponseDTO> user1History =
                bookingService.getBookingHistory(0, 10, user1.getUsername());

        assertEquals(2, user1History.totalElements());
        assertEquals(2, user1History.content().size());
        List<String> user1Refs = user1History.content().stream()
                .map(BookingDetailResponseDTO::bookingRef)
                .toList();
        assertTrue(user1Refs.contains(b1.bookingRef()));
        assertTrue(user1Refs.contains(b2.bookingRef()));
        assertFalse(user1Refs.contains(b3.bookingRef()), "User 1 must NEVER see User 2's booking");

        // User 2 queries history
        PageResponseDTO<BookingDetailResponseDTO> user2History =
                bookingService.getBookingHistory(0, 10, user2.getUsername());

        assertEquals(1, user2History.totalElements());
        assertEquals(1, user2History.content().size());
        assertEquals(b3.bookingRef(), user2History.content().get(0).bookingRef());
    }

    @Test
    void getBookingHistory_adminSeesAllBookingsAcrossUsers() {
        User user1 = createUser("admin-hist-u1", "ROLE_USER");
        User user2 = createUser("admin-hist-u2", "ROLE_USER");
        User admin = createUser("admin-hist-admin", "ROLE_ADMIN");

        BookingResponseDTO b1 = createPendingBookingForSeat(inventories.get(0), user1, 10);
        BookingResponseDTO b2 = createPendingBookingForSeat(inventories.get(1), user2, 10);

        PageResponseDTO<BookingDetailResponseDTO> adminHistory =
                bookingService.getBookingHistory(0, 10, admin.getUsername());

        assertTrue(adminHistory.totalElements() >= 2);
        List<String> refs = adminHistory.content().stream()
                .map(BookingDetailResponseDTO::bookingRef)
                .toList();
        assertTrue(refs.contains(b1.bookingRef()));
        assertTrue(refs.contains(b2.bookingRef()));
    }

    @Test
    void getBookingHistory_paginationAcrossMultiplePages_stableDeterministicOrdering() {
        User user = createUser("paginate-user", "ROLE_USER");

        List<BookingResponseDTO> createdBookings = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            createdBookings.add(createPendingBookingForSeat(inventories.get(i), user, 10));
        }

        // Query Page 0 with size 2
        PageResponseDTO<BookingDetailResponseDTO> page0 =
                bookingService.getBookingHistory(0, 2, user.getUsername());

        assertEquals(0, page0.page());
        assertEquals(2, page0.size());
        assertEquals(4, page0.totalElements());
        assertEquals(2, page0.totalPages());
        assertEquals(2, page0.content().size());
        assertTrue(page0.first());
        assertFalse(page0.last());
        assertTrue(page0.hasNext());
        assertFalse(page0.hasPrevious());

        // Query Page 1 with size 2
        PageResponseDTO<BookingDetailResponseDTO> page1 =
                bookingService.getBookingHistory(1, 2, user.getUsername());

        assertEquals(1, page1.page());
        assertEquals(2, page1.size());
        assertEquals(4, page1.totalElements());
        assertEquals(2, page1.totalPages());
        assertEquals(2, page1.content().size());
        assertFalse(page1.first());
        assertTrue(page1.last());
        assertFalse(page1.hasNext());
        assertTrue(page1.hasPrevious());

        // Verify deterministic sorting: newest first (bookingId DESC tie breaker)
        // Ensure no duplicate items across pages
        List<String> page0Refs = page0.content().stream().map(BookingDetailResponseDTO::bookingRef).toList();
        List<String> page1Refs = page1.content().stream().map(BookingDetailResponseDTO::bookingRef).toList();

        for (String ref : page0Refs) {
            assertFalse(page1Refs.contains(ref), "Page 0 and Page 1 must not contain duplicate items: " + ref);
        }

        // Verify order: each item in page0 has bookingId > items in page1 (or createdAt >=)
        Long minIdPage0 = page0.content().stream().mapToLong(BookingDetailResponseDTO::bookingId).min().orElseThrow();
        Long maxIdPage1 = page1.content().stream().mapToLong(BookingDetailResponseDTO::bookingId).max().orElseThrow();
        assertTrue(minIdPage0 > maxIdPage1, "Page 0 items must precede Page 1 items in DESC order");
    }

    @Test
    void getBookingHistory_invalidPaginationInputs_rejectedWithBadRequestException() {
        User user = createUser("invalid-page-user", "ROLE_USER");

        // Negative page
        BadRequestException ex1 = assertThrows(BadRequestException.class, () ->
                bookingService.getBookingHistory(-1, 10, user.getUsername())
        );
        assertTrue(ex1.getMessage().contains("Page index must not be less than zero"));

        // Zero page size
        BadRequestException ex2 = assertThrows(BadRequestException.class, () ->
                bookingService.getBookingHistory(0, 0, user.getUsername())
        );
        assertTrue(ex2.getMessage().contains("Page size must be greater than zero"));

        // Negative page size
        BadRequestException ex3 = assertThrows(BadRequestException.class, () ->
                bookingService.getBookingHistory(0, -5, user.getUsername())
        );
        assertTrue(ex3.getMessage().contains("Page size must be greater than zero"));

        // Oversized page size (> 100)
        BadRequestException ex4 = assertThrows(BadRequestException.class, () ->
                bookingService.getBookingHistory(0, 101, user.getUsername())
        );
        assertTrue(ex4.getMessage().contains("Page size must not exceed 100"));
    }

    private Station station(String code, String name) {
        Station s = new Station();
        s.setStationCode(code);
        s.setStationName(name);
        s.setCity(name);
        s.setState("Query State");
        return s;
    }

    private User createUser(String prefix, String role) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername(username);
        user.setFullName(prefix);
        user.setEmail(username + "@query-test.com");
        user.setPassword("not-used-password");
        user.setStatus("ACTIVE");
        user.setRole(role);
        return userRepository.save(user);
    }
}
