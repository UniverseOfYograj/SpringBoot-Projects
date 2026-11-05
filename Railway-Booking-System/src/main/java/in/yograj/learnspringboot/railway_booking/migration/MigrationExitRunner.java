package in.yograj.learnspringboot.railway_booking.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Opt-in migration-only exit coordinator.
 * <p>
 * Listens for {@link ApplicationReadyEvent} to ensure that singleton initialization,
 * Flyway migrations, Hibernate schema validation, and all {@code CommandLineRunner} /
 * {@code ApplicationRunner} beans have completed execution successfully before initiating
 * a graceful context shutdown and natural process exit with code 0.
 * <p>
 * Strictly opt-in via property {@code railway.migration.exit-after-startup=true}.
 * In normal production, local development, and test environments, this bean is never created.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(
        name = "railway.migration.exit-after-startup",
        havingValue = "true",
        matchIfMissing = false
)
public class MigrationExitRunner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger logger = LoggerFactory.getLogger(MigrationExitRunner.class);

    public static final String COMPLETION_MARKER =
            "[MIGRATION-EXIT] Spring Boot migration-only startup completed successfully. Initiating clean context shutdown.";

    private final Consumer<Integer> exitStrategy;

    public MigrationExitRunner() {
        this(System::exit);
    }

    MigrationExitRunner(Consumer<Integer> exitStrategy) {
        this.exitStrategy = Objects.requireNonNull(exitStrategy, "exitStrategy must not be null");
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        ConfigurableApplicationContext context = event.getApplicationContext();

        logger.info(COMPLETION_MARKER);
        System.out.flush();
        System.err.flush();

        int exitCode = SpringApplication.exit(context, () -> 0);
        exitStrategy.accept(exitCode);
    }
}
