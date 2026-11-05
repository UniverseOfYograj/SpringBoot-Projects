package in.yograj.learnspringboot.railway_booking.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionPropertiesValidationTests {

    @Test
    void productionPropertiesFileContainsMandatoryPlaceholdersWithoutFallback() throws Exception {
        ClassPathResource resource = new ClassPathResource("application-production.properties");
        assertThat(resource.exists()).isTrue();

        Properties props = PropertiesLoaderUtils.loadProperties(resource);

        // Mandatory DB credentials must be exact environment variable placeholders without defaults
        assertThat(props.getProperty("spring.datasource.url"))
                .isEqualTo("${DB_URL}");
        assertThat(props.getProperty("spring.datasource.username"))
                .isEqualTo("${DB_USERNAME}");
        assertThat(props.getProperty("spring.datasource.password"))
                .isEqualTo("${DB_PASSWORD}");

        // HikariCP conservative cloud max-lifetime (600,000 ms = 10 minutes)
        assertThat(props.getProperty("spring.datasource.hikari.max-lifetime"))
                .isEqualTo("${HIKARI_MAX_LIFETIME_MS:600000}");
        assertThat(props.getProperty("spring.datasource.hikari.maximum-pool-size"))
                .isEqualTo("${HIKARI_MAX_POOL_SIZE:8}");
        assertThat(props.getProperty("spring.datasource.hikari.minimum-idle"))
                .isEqualTo("${HIKARI_MIN_IDLE:2}");
        assertThat(props.getProperty("spring.datasource.hikari.data-source-properties.sslmode"))
                .isEqualTo("require");

        // Mandatory JWT secrets
        assertThat(props.getProperty("jwt.access-secret"))
                .isEqualTo("${JWT_ACCESS_SECRET}");
        assertThat(props.getProperty("jwt.refresh-secret"))
                .isEqualTo("${JWT_REFRESH_SECRET}");

        // Mandatory Redis credentials
        assertThat(props.getProperty("spring.data.redis.host"))
                .isEqualTo("${REDIS_HOST}");
        assertThat(props.getProperty("spring.data.redis.password"))
                .isEqualTo("${REDIS_PASSWORD}");
        assertThat(props.getProperty("spring.data.redis.ssl.enabled"))
                .isEqualTo("true");

        // Mandatory business config
        assertThat(props.getProperty("railway.security.cors.allowed-origins"))
                .isEqualTo("${CORS_ALLOWED_ORIGINS}");
        assertThat(props.getProperty("railway.fare.base-amount"))
                .isEqualTo("${RAILWAY_BASE_FARE}");

        // Payment gateway configuration
        assertThat(props.getProperty("railway.payment.mock-enabled"))
                .isEqualTo("false");
        assertThat(props.getProperty("railway.payment.simulated-enabled"))
                .isEqualTo("${RAILWAY_PAYMENT_SIMULATED_ENABLED:true}");
    }

    @Test
    void productionProfileResolvesDefaultHikariMaxLifetime() throws Exception {
        StandardEnvironment env = createProductionEnvironment(Map.of());

        assertThat(env.getProperty("spring.datasource.hikari.max-lifetime"))
                .isEqualTo("600000");
    }

    @Test
    void productionProfileHonorsHikariMaxLifetimeOverride() throws Exception {
        StandardEnvironment env = createProductionEnvironment(Map.of(
                "HIKARI_MAX_LIFETIME_MS", "450000"
        ));

        assertThat(env.getProperty("spring.datasource.hikari.max-lifetime"))
                .isEqualTo("450000");
    }

    @Test
    void productionProfileResolvesDirectNeonJdbcUrl() throws Exception {
        String testNeonUrl = "jdbc:postgresql://ep-railora.ap-southeast-1.aws.neon.tech:5432/railway_booking_prod?sslmode=require";
        StandardEnvironment env = createProductionEnvironment(Map.of(
                "DB_URL", testNeonUrl,
                "DB_USERNAME", "neondb_owner",
                "DB_PASSWORD", "test-secret"
        ));

        assertThat(env.getProperty("spring.datasource.url")).isEqualTo(testNeonUrl);
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("neondb_owner");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("test-secret");
    }

    @Test
    void productionProfileFailsFastWhenDbUrlPlaceholderIsMissing() throws Exception {
        // DB_URL intentionally not provided in environment map
        StandardEnvironment env = createProductionEnvironment(Map.of(
                "DB_USERNAME", "neondb_owner",
                "DB_PASSWORD", "test-secret"
        ));

        // When resolving spring.datasource.url, unresolvable placeholder must throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () ->
                env.resolveRequiredPlaceholders(env.getProperty("spring.datasource.url"))
        );
    }

    @Test
    void productionProfileFailsFastWhenDbPasswordPlaceholderIsMissing() throws Exception {
        StandardEnvironment env = createProductionEnvironment(Map.of(
                "DB_URL", "jdbc:postgresql://localhost:5432/railway_booking_prod?sslmode=require",
                "DB_USERNAME", "neondb_owner"
                // DB_PASSWORD intentionally missing
        ));

        assertThrows(IllegalArgumentException.class, () ->
                env.resolveRequiredPlaceholders(env.getProperty("spring.datasource.password"))
        );
    }

    @Test
    void productionProfileEnforcesNeonPostgreSqlSslModeRequire() throws Exception {
        ClassPathResource resource = new ClassPathResource("application-production.properties");
        Properties props = PropertiesLoaderUtils.loadProperties(resource);

        assertThat(props.getProperty("spring.datasource.hikari.data-source-properties.sslmode"))
                .isEqualTo("require");

        StandardEnvironment env = createProductionEnvironment(Map.of());
        assertThat(env.getProperty("spring.datasource.hikari.data-source-properties.sslmode"))
                .isEqualTo("require");
    }

    @Test
    void productionProfileResolvesDefaultHikariPoolSizingForRender() throws Exception {
        StandardEnvironment env = createProductionEnvironment(Map.of());

        assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size"))
                .isEqualTo("8");
        assertThat(env.getProperty("spring.datasource.hikari.minimum-idle"))
                .isEqualTo("2");
    }

    @Test
    void productionProfileHonorsHikariPoolSizingOverrides() throws Exception {
        StandardEnvironment env = createProductionEnvironment(Map.of(
                "HIKARI_MAX_POOL_SIZE", "12",
                "HIKARI_MIN_IDLE", "4"
        ));

        assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size"))
                .isEqualTo("12");
        assertThat(env.getProperty("spring.datasource.hikari.minimum-idle"))
                .isEqualTo("4");
    }

    @Test
    void productionProfileResolvesUpstashRedisSslEnabledByDefault() throws Exception {
        StandardEnvironment env = createProductionEnvironment(Map.of());

        assertThat(env.getProperty("spring.data.redis.ssl.enabled"))
                .isEqualTo("true");
    }

    @Test
    void productionProfileEnforcesRedisSslEvenIfEnvVarSetFalse() throws Exception {
        StandardEnvironment env = createProductionEnvironment(Map.of(
                "REDIS_SSL_ENABLED", "false"
        ));

        assertThat(env.getProperty("spring.data.redis.ssl.enabled"))
                .isEqualTo("true");
    }

    @Test
    void productionProfileResolvesPaymentGatewayFlagsForSimulatedMode() throws Exception {
        StandardEnvironment env = createProductionEnvironment(Map.of());

        assertThat(env.getProperty("railway.payment.simulated-enabled"))
                .isEqualTo("true");
        assertThat(env.getProperty("railway.payment.mock-enabled"))
                .isEqualTo("false");
        assertThat(env.getProperty("razorpay.enabled"))
                .isEqualTo("false");
    }

    @Test
    void productionProfileHonorsPaymentGatewayFlagOverridesWhileEnforcingMockDisabled() throws Exception {
        StandardEnvironment env = createProductionEnvironment(Map.of(
                "RAILWAY_PAYMENT_SIMULATED_ENABLED", "false",
                "RAILWAY_PAYMENT_MOCK_ENABLED", "true",
                "RAZORPAY_ENABLED", "true"
        ));

        assertThat(env.getProperty("railway.payment.simulated-enabled"))
                .isEqualTo("false");
        assertThat(env.getProperty("railway.payment.mock-enabled"))
                .isEqualTo("false");
        assertThat(env.getProperty("razorpay.enabled"))
                .isEqualTo("true");
    }

    private StandardEnvironment createProductionEnvironment(Map<String, Object> environmentOverrides) throws Exception {
        StandardEnvironment env = new StandardEnvironment();

        // Remove host OS environment variables and system properties to isolate tests from host environment
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);

        Properties baseProps = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        Properties prodProps = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-production.properties"));

        // Base properties loaded first, production overrides layered on top, followed by mock environment variables
        env.getPropertySources().addLast(new PropertiesPropertySource("base", baseProps));
        env.getPropertySources().addFirst(new PropertiesPropertySource("production", prodProps));

        if (!environmentOverrides.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource("envVars", new HashMap<>(environmentOverrides)));
        }

        return env;
    }
}
