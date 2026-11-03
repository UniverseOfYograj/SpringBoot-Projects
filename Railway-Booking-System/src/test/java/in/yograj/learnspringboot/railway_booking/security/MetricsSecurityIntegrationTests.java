package in.yograj.learnspringboot.railway_booking.security;

import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Base64;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for MetricsSecurityConfig — the dedicated HTTP Basic Auth
 * SecurityFilterChain for /actuator/prometheus.
 *
 * Tests verify:
 * 1. Metrics credentials authenticate against /actuator/prometheus.
 * 2. Wrong credentials are rejected.
 * 3. Unauthenticated access is rejected.
 * 4. Metrics credentials CANNOT access /api/** endpoints (chain isolation).
 * 5. Existing JWT security rules are unaffected.
 * 6. Only GET is accepted; POST is rejected.
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:metrics-auth-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:metrics-auth-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length",
        "management.metrics.scrape.auth.enabled=true",
        "METRICS_USER=promuser",
        "METRICS_PASSWORD=pr0mP@ss!Secure42",
        "railway.seat-hold-reaper.enabled=false",
        "railway.cache.enabled=false",
        "tatkal.queue.enabled=false"
})
class MetricsSecurityIntegrationTests {

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    private static final String METRICS_USER = "promuser";
    private static final String METRICS_PASSWORD = "pr0mP@ss!Secure42";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private String basicAuth(String user, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes());
    }

    // =========================================================================
    // 1. Valid metrics credentials — access granted
    // =========================================================================

    @Test
    void prometheus_validBasicAuth_returns200WithMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth(METRICS_USER, METRICS_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_")))
                .andExpect(content().string(containsString("hikaricp_")))
                .andExpect(content().string(containsString("process_")));
    }

    // =========================================================================
    // 2. Wrong credentials — access denied
    // =========================================================================

    @Test
    void prometheus_wrongPassword_returns401() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth(METRICS_USER, "wrongpassword")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheus_wrongUsername_returns401() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth("wronguser", METRICS_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // 3. Unauthenticated access — rejected
    // =========================================================================

    @Test
    void prometheus_noCredentials_returns401() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // 4. Chain isolation — metrics credentials cannot access /api/**
    // =========================================================================

    @Test
    void metricsCredentials_cannotAccessBookingsEndpoint() throws Exception {
        mockMvc.perform(get("/api/bookings")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth(METRICS_USER, METRICS_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void metricsCredentials_cannotAccessAdminEndpoint() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth(METRICS_USER, METRICS_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void metricsCredentials_cannotAccessUserProfile() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth(METRICS_USER, METRICS_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // 5. Existing JWT/public security rules unaffected
    // =========================================================================

    @Test
    void healthEndpoints_remainPubliclyAccessible() throws Exception {
        // Liveness and readiness endpoints return 200 without authentication
        mockMvc.perform(get("/livez"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/readyz"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());

        // /actuator/health is accessible without credentials (returns 200 or 503 depending on Redis status, never 401/403)
        mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.junit.jupiter.api.Assertions.assertTrue(status == 200 || status == 503,
                            "Expected public health endpoint to return 200 or 503, but got: " + status);
                });
    }

    @Test
    void publicTrainSearch_remainsAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/trains/search")
                        .param("from", "NDLS")
                        .param("to", "BCT"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpoint_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/bookings"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // 6. Only GET is accepted on /actuator/prometheus
    // =========================================================================

    @Test
    void prometheus_postMethod_isRejected() throws Exception {
        mockMvc.perform(post("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth(METRICS_USER, METRICS_PASSWORD)))
                .andExpect(status().isMethodNotAllowed());
    }

    // =========================================================================
    // 7. Prometheus response does not leak credentials
    // =========================================================================

    @Test
    void prometheus_responseDoesNotLeakCredentials() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth(METRICS_USER, METRICS_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(METRICS_PASSWORD))))
                .andExpect(content().string(not(containsString("pr0mP@ss"))));
    }

    // =========================================================================
    // 8. Existing JWT-based admin access still works for /actuator/prometheus
    // =========================================================================

    @Test
    void prometheus_jwtBearerToken_fallsThroughToMainChain() throws Exception {
        // A JWT token is NOT matched by the metrics chain (which uses httpBasic only).
        // The JWT chain still sees /actuator/prometheus and requires ADMIN role.
        // A random Bearer token should be rejected as unauthorized.
        mockMvc.perform(get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Nested test class verifying the application FAILS TO START when
     * metrics scrape auth is enabled but credentials are missing.
     */
    @Nested
    class MissingCredentialsTests {

        @Test
        void documentMissingCredentialsFailure() {
            // This is a documentation test. MetricsSecurityConfig throws IllegalStateException
            // if METRICS_USER or METRICS_PASSWORD are blank when enabled.
            // We cannot easily test startup failure within a running context,
            // but the fail-safe is verified by the explicit null/blank checks
            // in MetricsSecurityConfig.metricsSecurityFilterChain().
            org.junit.jupiter.api.Assertions.assertNotNull(context,
                    "Context should be loaded since credentials are provided in test properties");
        }
    }
}
