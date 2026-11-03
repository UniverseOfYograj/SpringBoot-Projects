package in.yograj.learnspringboot.railway_booking.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPollingSchedulerTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OutboxPollingScheduler.class)
            .withBean(OutboxEventRelay.class, () -> mock(OutboxEventRelay.class));

    @Nested
    @DisplayName("Application Context Wiring & Conditional Activation Tests")
    class ContextActivationTests {

        @Test
        @DisplayName("Scheduler bean is NOT created when railway.outbox.kafka.enabled is false")
        void whenKafkaDisabled_schedulerBeanIsNotActivated() {
            contextRunner
                    .withPropertyValues("railway.outbox.kafka.enabled=false")
                    .run(context -> assertThat(context).doesNotHaveBean(OutboxPollingScheduler.class));
        }

        @Test
        @DisplayName("Scheduler bean is NOT created when railway.outbox.kafka.enabled is missing")
        void whenKafkaPropertyMissing_schedulerBeanIsNotActivated() {
            contextRunner
                    .run(context -> assertThat(context).doesNotHaveBean(OutboxPollingScheduler.class));
        }

        @Test
        @DisplayName("Scheduler bean IS created when railway.outbox.kafka.enabled is true")
        void whenKafkaEnabled_schedulerBeanIsActivated() {
            contextRunner
                    .withPropertyValues("railway.outbox.kafka.enabled=true")
                    .run(context -> {
                        assertThat(context).hasSingleBean(OutboxPollingScheduler.class);
                        OutboxPollingScheduler scheduler = context.getBean(OutboxPollingScheduler.class);
                        assertTrue(scheduler.isSchedulerEnabled());
                        assertEquals(50, scheduler.getBatchSize());
                    });
        }

        @Test
        @DisplayName("Scheduler reads customized batch size and enabled flag from properties")
        void whenCustomPropertiesSupplied_schedulerBindsValues() {
            contextRunner
                    .withPropertyValues(
                            "railway.outbox.kafka.enabled=true",
                            "railway.outbox.scheduler.enabled=false",
                            "railway.outbox.scheduler.batch-size=25"
                    )
                    .run(context -> {
                        assertThat(context).hasSingleBean(OutboxPollingScheduler.class);
                        OutboxPollingScheduler scheduler = context.getBean(OutboxPollingScheduler.class);
                        assertFalse(scheduler.isSchedulerEnabled());
                        assertEquals(25, scheduler.getBatchSize());
                    });
        }
    }

    @Nested
    @DisplayName("Polling Execution & Error Handling Tests")
    class PollingExecutionTests {

        @Mock
        private OutboxEventRelay outboxEventRelay;

        @Test
        @DisplayName("When enabled, pollAndRelay invokes relay with configured batch size")
        void whenEnabled_invokesRelayWithConfiguredBatchSize() {
            OutboxPollingScheduler scheduler = new OutboxPollingScheduler(outboxEventRelay, true, 30);
            when(outboxEventRelay.relayPendingEvents(30)).thenReturn(5);

            scheduler.pollAndRelay();

            verify(outboxEventRelay, times(1)).relayPendingEvents(30);
        }

        @Test
        @DisplayName("When scheduler is disabled via configuration, polling does not call relay")
        void whenDisabled_doesNotInvokeRelay() {
            OutboxPollingScheduler scheduler = new OutboxPollingScheduler(outboxEventRelay, false, 50);

            scheduler.pollAndRelay();

            verify(outboxEventRelay, never()).relayPendingEvents(anyInt());
        }

        @Test
        @DisplayName("Polling exception is caught and logged, allowing subsequent scheduled cycles to succeed")
        void whenPollingThrowsException_cycleRecoversAndSubsequentInvocationSucceeds() {
            OutboxPollingScheduler scheduler = new OutboxPollingScheduler(outboxEventRelay, true, 50);

            // Cycle 1: throws an unexpected exception (e.g. database connection timeout)
            when(outboxEventRelay.relayPendingEvents(50))
                    .thenThrow(new RuntimeException("Transient DB timeout"))
                    .thenReturn(2);

            assertDoesNotThrow(scheduler::pollAndRelay,
                    "pollAndRelay must catch exceptions so scheduled thread is not terminated");

            // Cycle 2: subsequent cycle runs and executes successfully
            assertDoesNotThrow(scheduler::pollAndRelay,
                    "Subsequent cycle must execute without being blocked by prior error");

            verify(outboxEventRelay, times(2)).relayPendingEvents(50);
        }
    }
}
