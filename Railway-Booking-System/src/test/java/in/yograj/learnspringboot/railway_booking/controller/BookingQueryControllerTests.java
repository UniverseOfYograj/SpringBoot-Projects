package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.BookingDetailResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.PageResponseDTO;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:booking-query-controller-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length",
        "railway.seat-hold-reaper.enabled=false"
})
class BookingQueryControllerTests {

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    @MockitoBean
    private BookingService bookingService;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private BookingDetailResponseDTO sampleDetail(String bookingRef, String username) {
        return new BookingDetailResponseDTO(
                101L,
                bookingRef,
                55L,
                username,
                201L,
                "12951",
                "Rajdhani Express",
                "NDLS",
                "New Delhi",
                "BCT",
                "Mumbai Central",
                LocalDate.now().plusDays(2),
                LocalTime.of(16, 55),
                LocalTime.of(8, 35),
                10L,
                "B1",
                "SECOND_SEATING",
                1L,
                12,
                "WINDOW",
                501L,
                "CONFIRMED",
                "SUCCESS",
                new BigDecimal("750.00"),
                LocalDateTime.now()
        );
    }

    @Test
    @WithMockUser(username = "passenger1", roles = {"USER"})
    void getBookingByRef_authenticatedOwner_returns200AndDetailResponse() throws Exception {
        BookingDetailResponseDTO dto = sampleDetail("RB-DETAIL-001", "passenger1");
        when(bookingService.getBookingByRef("RB-DETAIL-001", "passenger1")).thenReturn(dto);

        mockMvc.perform(get("/api/bookings/RB-DETAIL-001")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingRef").value("RB-DETAIL-001"))
                .andExpect(jsonPath("$.username").value("passenger1"))
                .andExpect(jsonPath("$.trainNumber").value("12951"))
                .andExpect(jsonPath("$.fromStationCode").value("NDLS"))
                .andExpect(jsonPath("$.toStationCode").value("BCT"))
                .andExpect(jsonPath("$.coachNumber").value("B1"))
                .andExpect(jsonPath("$.seatNumber").value(12))
                .andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.amount").value(750.00));
    }

    @Test
    void getBookingByRef_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/bookings/RB-DETAIL-001")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "passenger1", roles = {"USER"})
    void getBookingByRef_notFound_returns404ApiErrorResponse() throws Exception {
        when(bookingService.getBookingByRef("RB-NONEXISTENT", "passenger1"))
                .thenThrow(new ResourceNotFoundException("Booking not found for reference: RB-NONEXISTENT"));

        mockMvc.perform(get("/api/bookings/RB-NONEXISTENT")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Booking not found for reference: RB-NONEXISTENT"));
    }

    @Test
    @WithMockUser(username = "attacker", roles = {"USER"})
    void getBookingByRef_unauthorizedUser_returns403ApiErrorResponse() throws Exception {
        when(bookingService.getBookingByRef("RB-VICTIM-001", "attacker"))
                .thenThrow(new AccessDeniedException("You are not authorized to view this booking"));

        mockMvc.perform(get("/api/bookings/RB-VICTIM-001")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("You are not authorized to view this booking"));
    }

    @Test
    @WithMockUser(username = "admin_user", roles = {"ADMIN"})
    void getBookingByRef_adminUser_returns200() throws Exception {
        BookingDetailResponseDTO dto = sampleDetail("RB-USER-001", "passenger1");
        when(bookingService.getBookingByRef("RB-USER-001", "admin_user")).thenReturn(dto);

        mockMvc.perform(get("/api/bookings/RB-USER-001")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingRef").value("RB-USER-001"))
                .andExpect(jsonPath("$.username").value("passenger1"));
    }

    @Test
    @WithMockUser(username = "passenger1", roles = {"USER"})
    void getBookingHistory_authenticated_returns200AndPageResponse() throws Exception {
        BookingDetailResponseDTO item1 = sampleDetail("RB-HIST-001", "passenger1");
        BookingDetailResponseDTO item2 = sampleDetail("RB-HIST-002", "passenger1");

        PageResponseDTO<BookingDetailResponseDTO> pageDto = new PageResponseDTO<>(
                List.of(item1, item2),
                0,
                10,
                2L,
                1,
                true,
                true,
                false,
                false
        );

        when(bookingService.getBookingHistory(0, 10, "passenger1")).thenReturn(pageDto);

        mockMvc.perform(get("/api/bookings")
                        .param("page", "0")
                        .param("size", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].bookingRef").value("RB-HIST-001"))
                .andExpect(jsonPath("$.content[1].bookingRef").value("RB-HIST-002"));
    }

    @Test
    void getBookingHistory_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/bookings")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "passenger1", roles = {"USER"})
    void getBookingHistory_invalidPage_returns400ApiErrorResponse() throws Exception {
        when(bookingService.getBookingHistory(eq(-1), anyInt(), anyString()))
                .thenThrow(new BadRequestException("Page index must not be less than zero"));

        mockMvc.perform(get("/api/bookings")
                        .param("page", "-1")
                        .param("size", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Page index must not be less than zero"));
    }

    @Test
    @WithMockUser(username = "passenger1", roles = {"USER"})
    void getBookingHistory_invalidSizeZero_returns400ApiErrorResponse() throws Exception {
        when(bookingService.getBookingHistory(anyInt(), eq(0), anyString()))
                .thenThrow(new BadRequestException("Page size must be greater than zero"));

        mockMvc.perform(get("/api/bookings")
                        .param("page", "0")
                        .param("size", "0")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Page size must be greater than zero"));
    }

    @Test
    @WithMockUser(username = "passenger1", roles = {"USER"})
    void getBookingHistory_oversizedPage_returns400ApiErrorResponse() throws Exception {
        when(bookingService.getBookingHistory(anyInt(), eq(101), anyString()))
                .thenThrow(new BadRequestException("Page size must not exceed 100"));

        mockMvc.perform(get("/api/bookings")
                        .param("page", "0")
                        .param("size", "101")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Page size must not exceed 100"));
    }
}
