package in.yograj.learnspringboot.railway_booking.integration.railradar;

import in.yograj.learnspringboot.railway_booking.integration.railradar.exception.RailRadarSyncException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class RailRadarClientTests {

    @Test
    void logsRetryAfterHeaderWhenUpstreamReturnsTooManyRequests() {
        RailRadarProperties properties = new RailRadarProperties();
        properties.setBaseUrl("https://railradar.test/v1");
        properties.setApiKey("test-key");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://railradar.test/v1/lookup/search/stations?q=DADN&limit=10"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "60"));

        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(RailRadarClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RailRadarClient client = new RailRadarClient(
                    builder.baseUrl(properties.getBaseUrl()).build(), properties);
            RailRadarSyncException exception = assertThrows(RailRadarSyncException.class,
                    () -> client.searchStations("DADN", 10));

            assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
            assertTrue(appender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().contains("Retry-After: 60")));
            server.verify();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void mapsUpstreamFailureToGatewayErrorWithoutReturningItsBody() {
        RailRadarProperties properties = new RailRadarProperties();
        properties.setBaseUrl("https://railradar.test/v1");
        properties.setApiKey("test-key");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://railradar.test/v1/lookup/search/stations?q=NDLS&limit=5"))
                .andExpect(header("X-API-Key", "test-key"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("upstream details must not be returned"));

        RailRadarClient client = new RailRadarClient(builder.baseUrl(properties.getBaseUrl()).build(), properties);
        RailRadarSyncException exception = assertThrows(RailRadarSyncException.class,
                () -> client.searchStations("NDLS", 5));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
        org.junit.jupiter.api.Assertions.assertFalse(
                exception.getMessage().contains("upstream details must not be returned"));
        server.verify();
    }

    @Test
    void reportsMissingApiKeyWithoutMakingAnUpstreamRequest() {
        RailRadarProperties properties = new RailRadarProperties();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RailRadarClient client = new RailRadarClient(builder.build(), properties);

        RailRadarSyncException exception = assertThrows(RailRadarSyncException.class,
                () -> client.searchStations("NDLS", 5));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        server.verify();
    }

    @Test
    void mapsAnUpstreamTimeoutToGatewayTimeout() {
        RailRadarProperties properties = new RailRadarProperties();
        properties.setBaseUrl("https://railradar.test/v1");
        properties.setApiKey("test-key");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://railradar.test/v1/lookup/search/stations?q=NDLS&limit=5"))
                .andRespond(request -> {
                    throw new ResourceAccessException("request timed out",
                            new HttpTimeoutException("request timed out"));
                });

        RailRadarClient client = new RailRadarClient(builder.baseUrl(properties.getBaseUrl()).build(), properties);
        RailRadarSyncException exception = assertThrows(RailRadarSyncException.class,
                () -> client.searchStations("NDLS", 5));

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, exception.getStatus());
        server.verify();
    }

    @Test
    void deserializesStationEnvelopeIntoExternalDtos() {
        RailRadarProperties properties = new RailRadarProperties();
        properties.setBaseUrl("https://railradar.test/v1");
        properties.setApiKey("test-key");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://railradar.test/v1/lookup/search/stations?q=NDLS&limit=5"))
                .andRespond(withSuccess("""
                        {"success":true,"data":[{"code":"NDLS","name":"New Delhi","city":"New Delhi"}],
                         "meta":{"traceId":"stub"}}
                        """, MediaType.APPLICATION_JSON));

        RailRadarClient client = new RailRadarClient(builder.baseUrl(properties.getBaseUrl()).build(), properties);
        var stations = client.searchStations("NDLS", 5);

        assertEquals(1, stations.size());
        assertEquals("NDLS", stations.getFirst().code());
        assertEquals("New Delhi", stations.getFirst().city());
        server.verify();
    }
}
