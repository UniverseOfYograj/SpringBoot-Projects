package in.yograj.learnspringboot.railway_booking.observability;

import com.zaxxer.hikari.HikariDataSource;
import in.yograj.learnspringboot.railway_booking.config.RedisCacheConfig;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:observability-tests;DB_CLOSE_DELAY=-1",
        "DB_USERNAME=sa",
        "DB_PASSWORD=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:observability-tests;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length"
})
class ObservabilityFoundationTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void hikariProperties_boundCorrectly() {
        assertInstanceOf(HikariDataSource.class, dataSource, "DataSource must be HikariDataSource");
        HikariDataSource hikari = (HikariDataSource) dataSource;

        assertEquals("RailoraHikariPool", hikari.getPoolName());
        assertEquals(15, hikari.getMaximumPoolSize());
        assertEquals(5, hikari.getMinimumIdle());
        assertEquals(20000L, hikari.getConnectionTimeout());
        assertEquals(300000L, hikari.getIdleTimeout());
        assertEquals(1200000L, hikari.getMaxLifetime());
        assertEquals(20000L, hikari.getLeakDetectionThreshold());
    }

    @Test
    void livenessEndpoint_returnsUpWithoutExternalDependencies() throws Exception {
        mockMvc.perform(get("/livez"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void readinessEndpoint_whenPostgresHealthyAndRedisDown_returnsUp() throws Exception {
        // Local Redis is offline by default on localhost:6379, causing redis health contributor to fail.
        // Readiness probe must still return UP because Redis is decoupled from readiness.
        mockMvc.perform(get("/readyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // Cache group independently observes Redis failure
        mockMvc.perform(get("/actuator/health/cache"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }

    @Test
    void healthEndpoints_neverExposeSensitiveConnectionDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(content().string(not(containsString("jdbc:"))))
                .andExpect(content().string(not(containsString("sa"))))
                .andExpect(content().string(not(containsString("password"))));
    }

    @Test
    void prometheusEndpoint_deniesUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "regularUser", roles = {"USER"})
    void prometheusEndpoint_deniesNonAdminRole() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "adminUser", roles = {"ADMIN"})
    void prometheusEndpoint_accessibleWithAdminRole_exposesJvmHikariAndProcessMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_")))
                .andExpect(content().string(containsString("hikaricp_")))
                .andExpect(content().string(containsString("process_")));
    }

    @Test
    void sensitiveActuatorEndpoints_areNotExposedOrDenied() throws Exception {
        // /actuator/env is not in management.endpoints.web.exposure.include -> 404 or 401
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/beans"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/configprops"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "adminUser", roles = {"ADMIN"})
    void sensitiveActuatorEndpoints_notExposedEvenForAdmin() throws Exception {
        // Because management.endpoints.web.exposure.include=health,prometheus
        // /actuator/env is not exposed at all over the web
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/actuator/beans"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/actuator/configprops"))
                .andExpect(status().isNotFound());
    }

    @Test
    void redisCacheFailOpen_whenRedisOperationsFail() {
        RedisCacheConfig.ResilientCacheErrorHandler handler = new RedisCacheConfig.ResilientCacheErrorHandler();

        assertDoesNotThrow(() ->
                handler.handleCacheGetError(new RuntimeException("Simulated Redis timeout"), null, "key-123"));

        assertDoesNotThrow(() ->
                handler.handleCachePutError(new RuntimeException("Simulated Redis write error"), null, "key-123", "val"));

        assertDoesNotThrow(() ->
                handler.handleCacheEvictError(new RuntimeException("Simulated Redis evict error"), null, "key-123"));

        assertDoesNotThrow(() ->
                handler.handleCacheClearError(new RuntimeException("Simulated Redis clear error"), null));
    }
}
