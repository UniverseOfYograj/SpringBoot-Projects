package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.BookingDetailResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentResponseDTO;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:booking-payment-controller-tests;DB_CLOSE_DELAY=-1",
        "DB_USERNAME=sa",
        "DB_PASSWORD=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:booking-payment-controller-tests;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length"
})
class BookingPaymentControllerTests {

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

    @Test
    @WithMockUser(username = "regular-user", roles = {"USER"})
    void payBooking_success_returns200AndPaymentResponse() throws Exception {
        PaymentResponseDTO responseDTO = new PaymentResponseDTO(
                "TXN-ABC12345",
                "RB-987654321",
                42L,
                new BigDecimal("500.00"),
                "SUCCESS",
                "CONFIRMED",
                "MOCK_UPI",
                "Payment successful and booking confirmed",
                LocalDateTime.now()
        );

        when(bookingService.processPayment(eq("RB-987654321"), any(PaymentRequestDTO.class), eq("regular-user")))
                .thenReturn(responseDTO);

        mockMvc.perform(post("/api/bookings/RB-987654321/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\": \"MOCK_UPI\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionRef").value("TXN-ABC12345"))
                .andExpect(jsonPath("$.bookingRef").value("RB-987654321"))
                .andExpect(jsonPath("$.paymentStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.amount").value(500.00));
    }

    @Test
    @WithMockUser(username = "regular-user", roles = {"USER"})
    void payBooking_expiredHold_returns400ApiErrorResponse() throws Exception {
        when(bookingService.processPayment(eq("RB-EXPIRED"), any(PaymentRequestDTO.class), eq("regular-user")))
                .thenThrow(new BadRequestException("Seat hold has expired. Booking can no longer be confirmed."));

        mockMvc.perform(post("/api/bookings/RB-EXPIRED/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Seat hold has expired. Booking can no longer be confirmed."))
                .andExpect(jsonPath("$.path").value("/api/bookings/RB-EXPIRED/pay"));
    }

    @Test
    @WithMockUser(username = "regular-user", roles = {"USER"})
    void payBooking_alreadyConfirmed_returns409ApiErrorResponse() throws Exception {
        when(bookingService.processPayment(eq("RB-CONFIRMED"), any(PaymentRequestDTO.class), eq("regular-user")))
                .thenThrow(new IllegalStateException("Booking has already been confirmed"));

        mockMvc.perform(post("/api/bookings/RB-CONFIRMED/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Booking has already been confirmed"))
                .andExpect(jsonPath("$.path").value("/api/bookings/RB-CONFIRMED/pay"));
    }

    @Test
    @WithMockUser(username = "regular-user", roles = {"USER"})
    void payBooking_idempotencyMismatch_returns409ApiErrorResponse() throws Exception {
        when(bookingService.processPayment(eq("RB-IDEMP-MISMATCH"), any(PaymentRequestDTO.class), eq("regular-user")))
                .thenThrow(new IllegalStateException("Idempotency key 'key-1' was previously used with different request parameters"));

        mockMvc.perform(post("/api/bookings/RB-IDEMP-MISMATCH/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\": \"key-1\", \"paymentMethod\": \"MOCK_CARD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Idempotency key 'key-1' was previously used with different request parameters"))
                .andExpect(jsonPath("$.path").value("/api/bookings/RB-IDEMP-MISMATCH/pay"));
    }

    @Test
    @WithMockUser(username = "regular-user", roles = {"USER"})
    void payBooking_dataIntegrityViolation_returns409ApiErrorResponse() throws Exception {
        when(bookingService.processPayment(eq("RB-CONFLICT"), any(PaymentRequestDTO.class), eq("regular-user")))
                .thenThrow(new DataIntegrityViolationException("Duplicate key value violates unique constraint uk_payment_booking_idempotency"));

        mockMvc.perform(post("/api/bookings/RB-CONFLICT/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\": \"key-conflict\", \"paymentMethod\": \"MOCK_UPI\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Database resource conflict: the request could not be completed due to conflicting data"))
                .andExpect(jsonPath("$.path").value("/api/bookings/RB-CONFLICT/pay"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("uk_payment_booking_idempotency"))));
    }

    @Test
    @WithMockUser(username = "attacker-user", roles = {"USER"})
    void payBooking_unauthorizedUser_returns403ApiErrorResponse() throws Exception {
        when(bookingService.processPayment(eq("RB-OTHER"), any(PaymentRequestDTO.class), eq("attacker-user")))
                .thenThrow(new AccessDeniedException("You are not authorized to pay for this booking"));

        mockMvc.perform(post("/api/bookings/RB-OTHER/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("You are not authorized to pay for this booking"))
                .andExpect(jsonPath("$.path").value("/api/bookings/RB-OTHER/pay"));
    }

    @Test
    @WithMockUser(username = "regular-user", roles = {"USER"})
    void payBooking_notFound_returns404ApiErrorResponse() throws Exception {
        when(bookingService.processPayment(eq("RB-UNKNOWN"), any(PaymentRequestDTO.class), eq("regular-user")))
                .thenThrow(new ResourceNotFoundException("Booking not found for reference: RB-UNKNOWN"));

        mockMvc.perform(post("/api/bookings/RB-UNKNOWN/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Booking not found for reference: RB-UNKNOWN"))
                .andExpect(jsonPath("$.path").value("/api/bookings/RB-UNKNOWN/pay"));
    }

    @Test
    void payBooking_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/bookings/RB-123/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "regular-user", roles = {"USER"})
    void cancelBooking_success_returns200AndDetailResponse() throws Exception {
        BookingDetailResponseDTO detailDTO = new BookingDetailResponseDTO(
                101L, "RB-CANCEL-1", 1L, "regular-user",
                10L, "12952", "Mumbai Rajdhani",
                "NDLS", "NEW DELHI", "BCT", "Mumbai Central",
                LocalDate.of(2026, 10, 27), LocalTime.of(16, 30), LocalTime.of(8, 35),
                1L, "B1", "SECOND_SEATING", 1L, 1, "SECOND_SEATING", 1L,
                "CANCELLED", "SUCCESS", new BigDecimal("500.00"), LocalDateTime.now()
        );

        when(bookingService.cancelBooking(eq("RB-CANCEL-1"), eq("regular-user")))
                .thenReturn(detailDTO);

        mockMvc.perform(post("/api/bookings/RB-CANCEL-1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingRef").value("RB-CANCEL-1"))
                .andExpect(jsonPath("$.bookingStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("SUCCESS"));
    }

    @Test
    @WithMockUser(username = "regular-user", roles = {"USER"})
    void cancelBooking_viaDelete_returns200AndDetailResponse() throws Exception {
        BookingDetailResponseDTO detailDTO = new BookingDetailResponseDTO(
                101L, "RB-CANCEL-DEL", 1L, "regular-user",
                10L, "12952", "Mumbai Rajdhani",
                "NDLS", "NEW DELHI", "BCT", "Mumbai Central",
                LocalDate.of(2026, 10, 27), LocalTime.of(16, 30), LocalTime.of(8, 35),
                1L, "B1", "SECOND_SEATING", 1L, 1, "SECOND_SEATING", 1L,
                "CANCELLED", "SUCCESS", new BigDecimal("500.00"), LocalDateTime.now()
        );

        when(bookingService.cancelBooking(eq("RB-CANCEL-DEL"), eq("regular-user")))
                .thenReturn(detailDTO);

        mockMvc.perform(delete("/api/bookings/RB-CANCEL-DEL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingRef").value("RB-CANCEL-DEL"))
                .andExpect(jsonPath("$.bookingStatus").value("CANCELLED"));
    }

    @Test
    void cancelBooking_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/bookings/RB-UNAUTH/cancel"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "attacker-user", roles = {"USER"})
    void cancelBooking_unauthorizedUser_returns403ApiErrorResponse() throws Exception {
        when(bookingService.cancelBooking(eq("RB-OTHER"), eq("attacker-user")))
                .thenThrow(new AccessDeniedException("You are not authorized to cancel this booking"));

        mockMvc.perform(post("/api/bookings/RB-OTHER/cancel"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("You are not authorized to cancel this booking"))
                .andExpect(jsonPath("$.path").value("/api/bookings/RB-OTHER/cancel"));
    }

    @Test
    @WithMockUser(username = "regular-user", roles = {"USER"})
    void cancelBooking_alreadyCancelled_returns409Conflict() throws Exception {
        when(bookingService.cancelBooking(eq("RB-ALREADY-CANCELLED"), eq("regular-user")))
                .thenThrow(new IllegalStateException("Booking has already been cancelled"));

        mockMvc.perform(post("/api/bookings/RB-ALREADY-CANCELLED/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Booking has already been cancelled"))
                .andExpect(jsonPath("$.path").value("/api/bookings/RB-ALREADY-CANCELLED/cancel"));
    }

    @Test
    @WithMockUser(username = "regular-user", roles = {"USER"})
    void cancelBooking_notFound_returns404() throws Exception {
        when(bookingService.cancelBooking(eq("RB-UNKNOWN"), eq("regular-user")))
                .thenThrow(new ResourceNotFoundException("Booking not found for reference: RB-UNKNOWN"));

        mockMvc.perform(post("/api/bookings/RB-UNKNOWN/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Booking not found for reference: RB-UNKNOWN"));
    }
}
