package in.yograj.learnspringboot.railway_booking.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtSecretConfigurationTests {

    private static final String VALID_ACCESS_SECRET = "valid-test-access-signing-secret-with-at-least-32-bytes!";
    private static final String VALID_REFRESH_SECRET = "valid-test-refresh-signing-secret-with-at-least-32-bytes!";
    private static final String SHORT_SECRET = "short-secret-under-32-bytes";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(JwtService.class);

    @Test
    @DisplayName("Fail startup when jwt.access-secret is null or empty")
    void whenAccessSecretIsMissing_thenStartupFailsWithClearNonSensitiveMessage() {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "refreshSecret", VALID_REFRESH_SECRET);

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::afterPropertiesSet);
        assertTrue(exception.getMessage().contains("JWT access secret is missing"));
        assertTrue(exception.getMessage().contains("jwt.access-secret"));
    }

    @Test
    @DisplayName("Fail startup when jwt.access-secret is blank whitespace")
    void whenAccessSecretIsBlank_thenStartupFailsWithClearNonSensitiveMessage() {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "accessSecret", "    ");
        ReflectionTestUtils.setField(service, "refreshSecret", VALID_REFRESH_SECRET);

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::afterPropertiesSet);
        assertTrue(exception.getMessage().contains("JWT access secret is missing"));
    }

    @Test
    @DisplayName("Fail startup when jwt.refresh-secret is null or empty")
    void whenRefreshSecretIsMissing_thenStartupFailsWithClearNonSensitiveMessage() {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "accessSecret", VALID_ACCESS_SECRET);

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::afterPropertiesSet);
        assertTrue(exception.getMessage().contains("JWT refresh secret is missing"));
        assertTrue(exception.getMessage().contains("jwt.refresh-secret"));
    }

    @Test
    @DisplayName("Fail startup when jwt.refresh-secret is blank whitespace")
    void whenRefreshSecretIsBlank_thenStartupFailsWithClearNonSensitiveMessage() {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "accessSecret", VALID_ACCESS_SECRET);
        ReflectionTestUtils.setField(service, "refreshSecret", "   ");

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::afterPropertiesSet);
        assertTrue(exception.getMessage().contains("JWT refresh secret is missing"));
    }

    @Test
    @DisplayName("Fail startup when jwt.access-secret is shorter than 32 bytes (256 bits)")
    void whenAccessSecretIsTooShort_thenStartupFailsWithClearNonSensitiveMessage() {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "accessSecret", SHORT_SECRET);
        ReflectionTestUtils.setField(service, "refreshSecret", VALID_REFRESH_SECRET);

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::afterPropertiesSet);
        assertTrue(exception.getMessage().contains("JWT access secret is too short"));
        assertTrue(exception.getMessage().contains("32 bytes"));
        assertFalse(exception.getMessage().contains(SHORT_SECRET), "Secret value must never be printed in errors");
    }

    @Test
    @DisplayName("Fail startup when jwt.refresh-secret is shorter than 32 bytes (256 bits)")
    void whenRefreshSecretIsTooShort_thenStartupFailsWithClearNonSensitiveMessage() {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "accessSecret", VALID_ACCESS_SECRET);
        ReflectionTestUtils.setField(service, "refreshSecret", SHORT_SECRET);

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::afterPropertiesSet);
        assertTrue(exception.getMessage().contains("JWT refresh secret is too short"));
        assertTrue(exception.getMessage().contains("32 bytes"));
        assertFalse(exception.getMessage().contains(SHORT_SECRET), "Secret value must never be printed in errors");
    }

    @Test
    @DisplayName("Succeed startup and token lifecycle when both secrets meet length requirements")
    void whenSecretsAreValid_thenStartupSucceedsAndGeneratesTokens() {
        JwtService service = new JwtService(VALID_ACCESS_SECRET, VALID_REFRESH_SECRET, 900_000L, 604_800_000L);
        service.afterPropertiesSet();

        String accessToken = service.generateAccessToken("passenger", "ROLE_USER");
        assertNotNull(accessToken);
        assertEquals("passenger", service.extractUsernameFromAccessToken(accessToken));
        assertFalse(service.isAccessTokenExpired(accessToken));

        String refreshToken = service.generateRefreshToken("passenger");
        assertNotNull(refreshToken);
        assertEquals("passenger", service.extractUsernameFromRefreshToken(refreshToken));
        assertFalse(service.isRefreshTokenExpired(refreshToken));
    }

    @Test
    @DisplayName("Spring ApplicationContext fails startup when access secret is missing")
    void springContextFailsWhenAccessSecretMissing() {
        contextRunner
                .withPropertyValues("jwt.refresh-secret=" + VALID_REFRESH_SECRET)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("JWT access secret is missing");
                });
    }

    @Test
    @DisplayName("Spring ApplicationContext fails startup when refresh secret is missing")
    void springContextFailsWhenRefreshSecretMissing() {
        contextRunner
                .withPropertyValues("jwt.access-secret=" + VALID_ACCESS_SECRET)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("JWT refresh secret is missing");
                });
    }

    @Test
    @DisplayName("Spring ApplicationContext fails startup when access secret is too short")
    void springContextFailsWhenAccessSecretTooShort() {
        contextRunner
                .withPropertyValues(
                        "jwt.access-secret=" + SHORT_SECRET,
                        "jwt.refresh-secret=" + VALID_REFRESH_SECRET)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("JWT access secret is too short")
                            .doesNotContain(SHORT_SECRET);
                });
    }

    @Test
    @DisplayName("Spring ApplicationContext fails startup when refresh secret is too short")
    void springContextFailsWhenRefreshSecretTooShort() {
        contextRunner
                .withPropertyValues(
                        "jwt.access-secret=" + VALID_ACCESS_SECRET,
                        "jwt.refresh-secret=" + SHORT_SECRET)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("JWT refresh secret is too short")
                            .doesNotContain(SHORT_SECRET);
                });
    }

    @Test
    @DisplayName("Spring ApplicationContext starts successfully when valid secrets are configured")
    void springContextStartsSuccessfullyWithValidSecrets() {
        contextRunner
                .withPropertyValues(
                        "jwt.access-secret=" + VALID_ACCESS_SECRET,
                        "jwt.refresh-secret=" + VALID_REFRESH_SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JwtService service = context.getBean(JwtService.class);
                    assertNotNull(service);
                    String token = service.generateAccessToken("alice", "ROLE_USER");
                    assertEquals("alice", service.extractUsernameFromAccessToken(token));
                });
    }
}
