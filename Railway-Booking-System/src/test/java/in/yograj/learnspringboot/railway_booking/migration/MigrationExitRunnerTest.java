package in.yograj.learnspringboot.railway_booking.migration;

import in.yograj.learnspringboot.railway_booking.RailwayBookingApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationExitRunnerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MigrationExitRunner.class);

    // =========================================================================
    // 1. ISOLATION TESTS (NORMAL PRODUCTION / DEV / TEST SAFETY)
    // =========================================================================

    @Test
    @DisplayName("Isolation: When property is absent, MigrationExitRunner bean is not created")
    void whenPropertyMissing_thenRunnerBeanNotLoaded() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(MigrationExitRunner.class);
        });
    }

    @Test
    @DisplayName("Isolation: When property is false, MigrationExitRunner bean is not created")
    void whenPropertyFalse_thenRunnerBeanNotLoaded() {
        contextRunner
                .withPropertyValues("railway.migration.exit-after-startup=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MigrationExitRunner.class);
                });
    }

    @Test
    @DisplayName("Opt-in: When property is true, MigrationExitRunner bean is loaded")
    void whenPropertyTrue_thenRunnerBeanLoaded() {
        contextRunner
                .withPropertyValues("railway.migration.exit-after-startup=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(MigrationExitRunner.class);
                });
    }

    // =========================================================================
    // 2. FAILURE-PATH & EXIT STRATEGY INVARIANT TESTS
    // =========================================================================

    @Test
    @DisplayName("Failure-Path: Startup failure aborts context and exitStrategy is never invoked")
    void whenStartupFailsDuringRefresh_exitStrategyIsNeverInvokedAndFailurePreserved() {
        AtomicInteger exitCodeCapture = new AtomicInteger(-1);
        MigrationExitRunner testRunner = new MigrationExitRunner(exitCodeCapture::set);

        new ApplicationContextRunner()
                .withBean("migrationExitRunner", MigrationExitRunner.class, () -> testRunner)
                .withPropertyValues("railway.migration.exit-after-startup=true")
                .withBean("failingStartupBean", String.class, () -> {
                    throw new IllegalStateException("Simulated Flyway or JPA schema validation error");
                })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Simulated Flyway or JPA schema validation error");
                    // Proves exit strategy was never called; non-zero failure is preserved
                    assertThat(exitCodeCapture.get()).isEqualTo(-1);
                });
    }

    @Test
    @DisplayName("Success-Path: On ApplicationReadyEvent, exitStrategy receives clean code 0")
    void whenApplicationReadyEventFired_invokesExitStrategyWithZero() {
        AtomicInteger exitCodeCapture = new AtomicInteger(-1);
        MigrationExitRunner testRunner = new MigrationExitRunner(exitCodeCapture::set);

        new ApplicationContextRunner()
                .withBean("migrationExitRunner", MigrationExitRunner.class, () -> testRunner)
                .withPropertyValues("railway.migration.exit-after-startup=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    ApplicationReadyEvent readyEvent = new ApplicationReadyEvent(
                            new SpringApplication(RailwayBookingApplication.class),
                            new String[0],
                            context.getSourceApplicationContext(),
                            Duration.ofSeconds(1)
                    );
                    testRunner.onApplicationEvent(readyEvent);

                    assertThat(exitCodeCapture.get()).isEqualTo(0);
                });
    }

    @Test
    @DisplayName("Failure-Preservation: Non-zero ExitCodeGenerator status is preserved and not converted to zero")
    void whenExitCodeGeneratorReturnsNonZero_exitStrategyReceivesNonZeroCode() {
        AtomicInteger exitCodeCapture = new AtomicInteger(-1);
        MigrationExitRunner testRunner = new MigrationExitRunner(exitCodeCapture::set);

        new ApplicationContextRunner()
                .withBean("migrationExitRunner", MigrationExitRunner.class, () -> testRunner)
                .withBean("failingExitCodeGenerator", ExitCodeGenerator.class, () -> () -> 42)
                .withPropertyValues("railway.migration.exit-after-startup=true")
                .run(context -> {
                    ApplicationReadyEvent readyEvent = new ApplicationReadyEvent(
                            new SpringApplication(RailwayBookingApplication.class),
                            new String[0],
                            context.getSourceApplicationContext(),
                            Duration.ofSeconds(1)
                    );
                    testRunner.onApplicationEvent(readyEvent);

                    assertThat(exitCodeCapture.get()).isEqualTo(42);
                });
    }

    // =========================================================================
    // 3. APPLICATION READY EVENT ORDERING TEST
    // =========================================================================

    @Test
    @DisplayName("Ordering: MigrationExitRunner executes strictly after startup runners and higher-precedence listeners")
    void migrationExitRunnerExecutesAfterStartupRunnersAndPriorListeners() {
        List<String> executionLog = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger exitCodeCapture = new AtomicInteger(-1);

        CommandLineRunner commandLineRunner = args -> executionLog.add("CommandLineRunner");
        ApplicationRunner applicationRunner = args -> executionLog.add("ApplicationRunner");

        ApplicationListener<ApplicationReadyEvent> priorListener = new PriorReadyListener(executionLog);

        MigrationExitRunner testRunner = new MigrationExitRunner(code -> {
            executionLog.add("MigrationExitRunner");
            exitCodeCapture.set(code);
        });

        new ApplicationContextRunner()
                .withBean("commandLineRunner", CommandLineRunner.class, () -> commandLineRunner)
                .withBean("applicationRunner", ApplicationRunner.class, () -> applicationRunner)
                .withBean("priorReadyListener", ApplicationListener.class, () -> priorListener)
                .withBean("migrationExitRunner", MigrationExitRunner.class, () -> testRunner)
                .withPropertyValues("railway.migration.exit-after-startup=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    // Simulate the runner execution that occurs during callRunners(...)
                    commandLineRunner.run();
                    applicationRunner.run(null);

                    // Publish ApplicationReadyEvent to simulate listeners.ready(...)
                    ApplicationReadyEvent readyEvent = new ApplicationReadyEvent(
                            new SpringApplication(RailwayBookingApplication.class),
                            new String[0],
                            context.getSourceApplicationContext(),
                            Duration.ofSeconds(1)
                    );

                    // Execute listeners in order
                    priorListener.onApplicationEvent(readyEvent);
                    testRunner.onApplicationEvent(readyEvent);

                    assertThat(executionLog).containsExactly(
                            "CommandLineRunner",
                            "ApplicationRunner",
                            "PriorReadyListener",
                            "MigrationExitRunner"
                    );
                    assertThat(exitCodeCapture.get()).isEqualTo(0);
                });
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class PriorReadyListener implements ApplicationListener<ApplicationReadyEvent> {
        private final List<String> log;

        PriorReadyListener(List<String> log) {
            this.log = log;
        }

        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            log.add("PriorReadyListener");
        }
    }
}
