package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.integration.railradar.exception.RailRadarSyncException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTests {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestExceptionController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("ResourceNotFoundException returns 404 with standard ApiErrorResponse schema")
    void handleResourceNotFoundReturns404WithStandardSchema() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").value(notNullValue()))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Requested entity was not found"))
                .andExpect(jsonPath("$.path").value("/test/not-found"));
    }

    @Test
    @DisplayName("BadRequestException returns 400 with standard ApiErrorResponse schema")
    void handleBadRequestReturns400WithStandardSchema() throws Exception {
        mockMvc.perform(get("/test/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").value(notNullValue()))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Malformed request payload"))
                .andExpect(jsonPath("$.path").value("/test/bad-request"));
    }

    @Test
    @DisplayName("IllegalArgumentException returns 400 with standard ApiErrorResponse schema")
    void handleIllegalArgumentReturns400WithStandardSchema() throws Exception {
        mockMvc.perform(get("/test/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").value(notNullValue()))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Illegal parameter supplied"))
                .andExpect(jsonPath("$.path").value("/test/illegal-argument"));
    }

    @Test
    @DisplayName("IllegalStateException returns 409 Conflict with standard ApiErrorResponse schema")
    void handleIllegalStateReturns409ConflictWithStandardSchema() throws Exception {
        mockMvc.perform(get("/test/illegal-state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.timestamp").value(notNullValue()))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Resource state conflict occurred"))
                .andExpect(jsonPath("$.path").value("/test/illegal-state"));
    }

    @Test
    @DisplayName("DataIntegrityViolationException returns 409 Conflict with sanitized ApiErrorResponse schema")
    void handleDataIntegrityViolationReturns409WithSanitizedSchema() throws Exception {
        mockMvc.perform(get("/test/data-integrity-violation"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.timestamp").value(notNullValue()))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Database resource conflict: the request could not be completed due to conflicting data"))
                .andExpect(jsonPath("$.path").value("/test/data-integrity-violation"));
    }

    @Test
    @DisplayName("AccessDeniedException returns 403 Forbidden with standard ApiErrorResponse schema")
    void handleAccessDeniedReturns403WithStandardSchema() throws Exception {
        mockMvc.perform(get("/test/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.timestamp").value(notNullValue()))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("You do not have permission"))
                .andExpect(jsonPath("$.path").value("/test/access-denied"));
    }

    @Test
    @DisplayName("BadCredentialsException returns 401 Unauthorized with standard ApiErrorResponse schema")
    void handleBadCredentialsReturns401WithStandardSchema() throws Exception {
        mockMvc.perform(get("/test/bad-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.timestamp").value(notNullValue()))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid username/email or password"))
                .andExpect(jsonPath("$.path").value("/test/bad-credentials"));
    }

    @Test
    @DisplayName("MethodArgumentNotValidException returns 400 with fieldErrors map")
    void handleValidationReturns400WithFieldErrorsMap() throws Exception {
        String invalidBody = """
                {
                    "name": ""
                }
                """;

        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").value(notNullValue()))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Validation failed for one or more fields"))
                .andExpect(jsonPath("$.path").value("/test/validation"))
                .andExpect(jsonPath("$.fieldErrors.name").value("Name is required"))
                .andExpect(jsonPath("$.fieldErrors.count").value("Count is required"));
    }

    @Test
    @DisplayName("RailRadarSyncException returns dynamic HTTP status with standard schema")
    void handleRailRadarSyncReturnsCustomStatusWithStandardSchema() throws Exception {
        mockMvc.perform(get("/test/rail-radar-error"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.timestamp").value(notNullValue()))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value("RailRadar gateway unavailable"))
                .andExpect(jsonPath("$.path").value("/test/rail-radar-error"));
    }

    @RestController
    @RequestMapping("/test")
    static class TestExceptionController {

        @GetMapping("/not-found")
        void throwNotFound() {
            throw new ResourceNotFoundException("Requested entity was not found");
        }

        @GetMapping("/bad-request")
        void throwBadRequest() {
            throw new BadRequestException("Malformed request payload");
        }

        @GetMapping("/illegal-argument")
        void throwIllegalArgument() {
            throw new IllegalArgumentException("Illegal parameter supplied");
        }

        @GetMapping("/illegal-state")
        void throwIllegalState() {
            throw new IllegalStateException("Resource state conflict occurred");
        }

        @GetMapping("/data-integrity-violation")
        void throwDataIntegrityViolation() {
            throw new DataIntegrityViolationException("Unique constraint violation [uk_test_constraint] on table users");
        }

        @GetMapping("/access-denied")
        void throwAccessDenied() {
            throw new AccessDeniedException("You do not have permission");
        }

        @GetMapping("/bad-credentials")
        void throwBadCredentials() {
            throw new BadCredentialsException("Bad credentials provided");
        }

        @PostMapping("/validation")
        void triggerValidation(@Valid @RequestBody SampleTestDTO body) {
        }

        @GetMapping("/rail-radar-error")
        void throwRailRadar() {
            throw new RailRadarSyncException(HttpStatus.SERVICE_UNAVAILABLE, "RailRadar gateway unavailable");
        }
    }

    record SampleTestDTO(
            @NotBlank(message = "Name is required") String name,
            @NotNull(message = "Count is required") Integer count
    ) {
    }
}
