package in.yograj.learnspringboot.railway_booking.outbox;

import in.yograj.learnspringboot.railway_booking.entity.OutboxEvent;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.NetworkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kafka-publisher-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length",
        "railway.outbox.kafka.enabled=true",
        "railway.outbox.kafka.topic=test-outbox-topic",
        "railway.outbox.kafka.timeout-ms=2000"
})
class KafkaOutboxMessagePublisherTests {

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private OutboxMessagePublisher outboxMessagePublisher;

    private KafkaOutboxMessagePublisher kafkaPublisher;

    @BeforeEach
    void setup() {
        this.kafkaPublisher = new KafkaOutboxMessagePublisher(kafkaTemplate, "test-outbox-topic", 2000);
    }

    @Test
    void wiring_whenKafkaEnabled_kafkaPublisherIsActiveBean() {
        assertNotNull(outboxMessagePublisher);
        assertInstanceOf(KafkaOutboxMessagePublisher.class, outboxMessagePublisher);
        KafkaOutboxMessagePublisher wired = (KafkaOutboxMessagePublisher) outboxMessagePublisher;
        assertEquals("test-outbox-topic", wired.getTopic());
        assertEquals(2000, wired.getTimeoutMs());
    }

    @Test
    void publish_successfulBrokerAck_completesNormally() throws Exception {
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID().toString(),
                "BOOKING",
                "RB-KAFKA-001",
                "BOOKING_CONFIRMED",
                "{\"bookingRef\":\"RB-KAFKA-001\"}",
                LocalDateTime.now()
        );

        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("test-outbox-topic", 0),
                0L,
                0,
                System.currentTimeMillis(),
                0,
                0
        );
        SendResult<String, String> sendResult = new SendResult<>(null, metadata);
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(sendResult);

        when(kafkaTemplate.send(eq("test-outbox-topic"), eq("RB-KAFKA-001"), anyString()))
                .thenReturn(future);

        assertDoesNotThrow(() -> kafkaPublisher.publish(event));
        verify(kafkaTemplate, times(1)).send("test-outbox-topic", "RB-KAFKA-001", event.getPayload());
    }

    @Test
    void publish_brokerTimeout_throwsExceptionLeavingEventRetryable() {
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID().toString(),
                "BOOKING",
                "RB-KAFKA-TIMEOUT",
                "BOOKING_CONFIRMED",
                "{\"bookingRef\":\"RB-KAFKA-TIMEOUT\"}",
                LocalDateTime.now()
        );

        CompletableFuture<SendResult<String, String>> pendingFuture = new CompletableFuture<>();

        KafkaOutboxMessagePublisher shortTimeoutPublisher =
                new KafkaOutboxMessagePublisher(kafkaTemplate, "test-outbox-topic", 50);

        when(kafkaTemplate.send(eq("test-outbox-topic"), eq("RB-KAFKA-TIMEOUT"), anyString()))
                .thenReturn(pendingFuture);

        assertThrows(TimeoutException.class, () -> shortTimeoutPublisher.publish(event));
    }

    @Test
    void publish_brokerNetworkError_throwsExecutionExceptionLeavingEventRetryable() {
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID().toString(),
                "PAYMENT",
                "TXN-KAFKA-ERR",
                "PAYMENT_FAILED",
                "{\"txnRef\":\"TXN-KAFKA-ERR\"}",
                LocalDateTime.now()
        );

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new NetworkException("Broker connection refused"));

        when(kafkaTemplate.send(eq("test-outbox-topic"), eq("TXN-KAFKA-ERR"), anyString()))
                .thenReturn(failedFuture);

        ExecutionException ex = assertThrows(ExecutionException.class, () -> kafkaPublisher.publish(event));
        assertInstanceOf(NetworkException.class, ex.getCause());
        assertEquals("Broker connection refused", ex.getCause().getMessage());
    }
}
