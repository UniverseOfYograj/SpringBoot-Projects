package in.yograj.learnspringboot.railway_booking;

import in.yograj.learnspringboot.railway_booking.entity.User;
import in.yograj.learnspringboot.railway_booking.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminUserSeedingTests {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private RailwayBookingApplication application;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        application = new RailwayBookingApplication();
    }

    @Configuration
    static class AdminSeedingTestConfig {
        @Bean
        public RailwayBookingApplication railwayBookingApplication() {
            return new RailwayBookingApplication();
        }

        @Bean
        @ConditionalOnProperty(name = "railway.data-seed.admin.enabled", havingValue = "true", matchIfMissing = false)
        public CommandLineRunner seedAdminUser(
                UserRepository userRepository,
                PasswordEncoder passwordEncoder,
                @Value("${railway.data-seed.admin.password:${SEED_ADMIN_PASSWORD:}}") String configuredPassword) {
            return railwayBookingApplication().seedAdminUser(userRepository, passwordEncoder, configuredPassword);
        }
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(AdminSeedingTestConfig.class)
                .withBean(UserRepository.class, () -> userRepository)
                .withBean(PasswordEncoder.class, () -> passwordEncoder);
    }

    @Test
    @DisplayName("Fail fast with clear error when admin seeding enabled but password is null")
    void whenAdminSeedingEnabled_butPasswordIsNull_thenFailsFast() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> application.seedAdminUser(userRepository, passwordEncoder, null)
        );

        assertTrue(exception.getMessage().contains("SEED_ADMIN_PASSWORD is missing or blank"));
        assertTrue(exception.getMessage().contains("Admin user seeding is enabled"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Fail fast with clear error when admin seeding enabled but password is empty")
    void whenAdminSeedingEnabled_butPasswordIsEmpty_thenFailsFast() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> application.seedAdminUser(userRepository, passwordEncoder, "")
        );

        assertTrue(exception.getMessage().contains("SEED_ADMIN_PASSWORD is missing or blank"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Fail fast with clear error when admin seeding enabled but password is blank spaces")
    void whenAdminSeedingEnabled_butPasswordIsBlank_thenFailsFast() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> application.seedAdminUser(userRepository, passwordEncoder, "    \t \n ")
        );

        assertTrue(exception.getMessage().contains("SEED_ADMIN_PASSWORD is missing or blank"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Successfully seed admin user when a non-blank password is provided; never use fallback default")
    void whenAdminSeedingEnabled_withConfiguredPassword_thenAdminCreatedSuccessfully() throws Exception {
        String configuredPassword = "SuperSecureAdminPassword#2026";
        when(userRepository.existsByUsername("admin")).thenReturn(false);

        CommandLineRunner runner = application.seedAdminUser(userRepository, passwordEncoder, configuredPassword);
        assertNotNull(runner);

        runner.run();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(userCaptor.capture());

        User savedAdmin = userCaptor.getValue();
        assertEquals("admin", savedAdmin.getUsername());
        assertEquals("ROLE_ADMIN", savedAdmin.getRole());
        assertEquals("ACTIVE", savedAdmin.getStatus());
        assertEquals("admin@railwaybooking.local", savedAdmin.getEmail());
        assertEquals("0000000000", savedAdmin.getPhoneNumber());

        // Verify the saved password matches the configured password and NOT the legacy fallback
        assertTrue(passwordEncoder.matches(configuredPassword, savedAdmin.getPassword()),
                "Saved password hash must match configured password");
        assertFalse(passwordEncoder.matches("Admin@123", savedAdmin.getPassword()),
                "Default fallback password Admin@123 must never be used");
    }

    @Test
    @DisplayName("Do not overwrite or recreate admin if user already exists")
    void whenAdminUserAlreadyExists_andValidPasswordConfigured_thenExistingAdminNotOverwritten() throws Exception {
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        CommandLineRunner runner = application.seedAdminUser(userRepository, passwordEncoder, "ValidPassword123!");
        runner.run();

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Spring ApplicationContext fails fast when admin seeding enabled without password")
    void springContextFailsFast_whenAdminSeedingEnabled_withoutPassword() {
        contextRunner()
                .withPropertyValues("railway.data-seed.admin.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("SEED_ADMIN_PASSWORD is missing or blank");
                });
    }

    @Test
    @DisplayName("Spring ApplicationContext fails fast when admin seeding enabled with blank password")
    void springContextFailsFast_whenAdminSeedingEnabled_withBlankPassword() {
        contextRunner()
                .withPropertyValues(
                        "railway.data-seed.admin.enabled=true",
                        "SEED_ADMIN_PASSWORD=   ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("SEED_ADMIN_PASSWORD is missing or blank");
                });
    }

    @Test
    @DisplayName("Spring ApplicationContext disables admin seeding by default when property is omitted")
    void springContextDisablesAdminSeedingByDefault() {
        contextRunner()
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("seedAdminUser");
                });
    }

    @Test
    @DisplayName("Spring ApplicationContext starts and registers seedAdminUser when valid password configured")
    void springContextSucceeds_whenAdminSeedingEnabled_withValidPassword() {
        contextRunner()
                .withPropertyValues(
                        "railway.data-seed.admin.enabled=true",
                        "SEED_ADMIN_PASSWORD=CustomAdminSecret123!")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("seedAdminUser");
                });
    }
}
