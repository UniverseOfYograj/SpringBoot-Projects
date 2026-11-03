package in.yograj.learnspringboot.railway_booking.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Dedicated SecurityFilterChain for the Prometheus metrics scrape endpoint.
 *
 * <p>This chain has {@code @Order(1)} so it matches BEFORE the main JWT-based
 * SecurityFilterChain (which defaults to {@code @Order(SecurityProperties.DEFAULT_FILTER_ORDER)}).
 * HTTP Basic authentication is enabled ONLY for {@code /actuator/prometheus}.
 * The main JWT chain is completely unaffected.</p>
 *
 * <p>Activation requires the property {@code management.metrics.scrape.auth.enabled=true}
 * AND non-blank environment variables {@code METRICS_USER} and {@code METRICS_PASSWORD}.</p>
 *
 * <p>In production, set {@code management.metrics.scrape.auth.enabled=false} (the default)
 * and protect the endpoint via network policy instead.</p>
 */
@Configuration
@ConditionalOnProperty(name = "management.metrics.scrape.auth.enabled", havingValue = "true")
public class MetricsSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(MetricsSecurityConfig.class);

    @Bean
    @Order(1)
    public SecurityFilterChain metricsSecurityFilterChain(
            HttpSecurity http,
            PasswordEncoder passwordEncoder,
            @Value("${METRICS_USER:}") String metricsUser,
            @Value("${METRICS_PASSWORD:}") String metricsPassword) throws Exception {

        if (metricsUser == null || metricsUser.isBlank()) {
            throw new IllegalStateException(
                    "METRICS_USER environment variable must be set when management.metrics.scrape.auth.enabled=true");
        }
        if (metricsPassword == null || metricsPassword.isBlank()) {
            throw new IllegalStateException(
                    "METRICS_PASSWORD environment variable must be set when management.metrics.scrape.auth.enabled=true");
        }

        log.info("Metrics scrape endpoint configured with HTTP Basic authentication for user '{}'", metricsUser);

        UserDetails metricsUserDetails = User.builder()
                .username(metricsUser)
                .password(passwordEncoder.encode(metricsPassword))
                .roles("ADMIN")
                .build();

        InMemoryUserDetailsManager metricsUserDetailsManager =
                new InMemoryUserDetailsManager(metricsUserDetails);

        http
                .securityMatcher("/actuator/prometheus")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/prometheus").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults())
                .userDetailsService(metricsUserDetailsManager);

        return http.build();
    }
}
