package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.JourneyRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.JourneyResponseDTO;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.service.JourneyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:journey-ctrl-tests;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:journey-ctrl-tests;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class JourneyControllerValidationTests {

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    @MockitoBean
    private JourneyService journeyService;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Valid route-based payload returns 201 Created")
    void validRouteBasedPayloadReturns201Created() throws Exception {
        JourneyResponseDTO mockResponse = new JourneyResponseDTO(
                100L, 1L, "EXP123", "Express",
                10L, "STA", "Station A",
                20L, "STB", "Station B",
                LocalDate.now().plusDays(2),
                LocalTime.of(8, 0), LocalTime.of(12, 0),
                50L,
                LocalDateTime.now().plusDays(2).withHour(8).withMinute(0),
                LocalDateTime.now().plusDays(2).withHour(12).withMinute(0)
        );
        when(journeyService.createJourney(any(JourneyRequestDTO.class))).thenReturn(mockResponse);

        String routePayload = """
                {
                    "trainId": 1,
                    "journeyDate": "2026-11-15",
                    "trainRouteId": 50
                }
                """;

        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routePayload))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Valid station-based payload returns 201 Created")
    void validStationBasedPayloadReturns201Created() throws Exception {
        JourneyResponseDTO mockResponse = new JourneyResponseDTO(
                101L, 1L, "EXP123", "Express",
                10L, "STA", "Station A",
                20L, "STB", "Station B",
                LocalDate.now().plusDays(2),
                LocalTime.of(8, 0), LocalTime.of(12, 0),
                null,
                LocalDateTime.now().plusDays(2).withHour(8).withMinute(0),
                LocalDateTime.now().plusDays(2).withHour(12).withMinute(0)
        );
        when(journeyService.createJourney(any(JourneyRequestDTO.class))).thenReturn(mockResponse);

        String stationPayload = """
                {
                    "trainId": 1,
                    "fromStationId": 10,
                    "toStationId": 20,
                    "journeyDate": "2026-11-15",
                    "departureTime": "08:00:00",
                    "arrivalTime": "12:00:00"
                }
                """;

        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stationPayload))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Invalid payload with missing route and stations returns 400 Bad Request")
    void invalidPayloadMissingRouteAndStationsReturns400() throws Exception {
        String invalidPayload = """
                {
                    "trainId": 1,
                    "journeyDate": "2026-11-15"
                }
                """;

        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Invalid payload with same from and to stations returns 400 Bad Request")
    void invalidPayloadSameStationsReturns400() throws Exception {
        String invalidPayload = """
                {
                    "trainId": 1,
                    "fromStationId": 10,
                    "toStationId": 10,
                    "journeyDate": "2026-11-15",
                    "departureTime": "08:00:00",
                    "arrivalTime": "12:00:00"
                }
                """;

        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Invalid payload with missing trainId returns 400 Bad Request")
    void invalidPayloadMissingTrainIdReturns400() throws Exception {
        String invalidPayload = """
                {
                    "trainRouteId": 50,
                    "journeyDate": "2026-11-15"
                }
                """;

        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }
}
