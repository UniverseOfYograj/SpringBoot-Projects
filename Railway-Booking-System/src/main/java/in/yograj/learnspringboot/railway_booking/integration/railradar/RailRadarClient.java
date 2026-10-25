package in.yograj.learnspringboot.railway_booking.integration.railradar;

import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.RailRadarApiEnvelope;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.RailRadarStationDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.RailRadarTrainDetailsDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.exception.RailRadarSyncException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.util.List;

@Component
public class RailRadarClient {

    private static final Logger logger = LoggerFactory.getLogger(RailRadarClient.class);

    private static final ParameterizedTypeReference<RailRadarApiEnvelope<List<RailRadarStationDTO>>>
            STATION_RESPONSE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<RailRadarApiEnvelope<RailRadarTrainDetailsDTO>>
            TRAIN_RESPONSE = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final RailRadarProperties properties;

    public RailRadarClient(RestClient railRadarRestClient, RailRadarProperties properties) {
        this.restClient = railRadarRestClient;
        this.properties = properties;
    }

    public List<RailRadarStationDTO> searchStations(String query, int limit) {
        if (!List.of(5, 10, 20, 50).contains(limit)) {
            throw new RailRadarSyncException(HttpStatus.BAD_REQUEST,
                    "RailRadar station search limit must be one of 5, 10, 20, or 50");
        }
        requireApiKey();

        try {
            RailRadarApiEnvelope<List<RailRadarStationDTO>> response = restClient.get()
                    .uri(builder -> builder.path("/lookup/search/stations")
                            .queryParam("q", query)
                            .queryParam("limit", limit)
                            .build())
                    .header("X-API-Key", properties.getApiKey())
                    .retrieve()
                    .body(STATION_RESPONSE);
            return requireData(response, "station search");
        } catch (RestClientResponseException exception) {
            throw upstreamStatus(exception);
        } catch (RestClientException exception) {
            throw new RailRadarSyncException(isTimeout(exception)
                    ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                    "RailRadar station search failed or returned an invalid response", exception);
        }
    }

    public RailRadarTrainDetailsDTO getTrainDetails(String trainNumber) {
        requireApiKey();

        try {
            RailRadarApiEnvelope<RailRadarTrainDetailsDTO> response = restClient.get()
                    .uri("/trains/{number}", trainNumber)
                    .header("X-API-Key", properties.getApiKey())
                    .retrieve()
                    .body(TRAIN_RESPONSE);
            return requireData(response, "train details");
        } catch (RestClientResponseException exception) {
            throw upstreamStatus(exception);
        } catch (RestClientException exception) {
            throw new RailRadarSyncException(isTimeout(exception)
                    ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                    "RailRadar train details request failed or returned an invalid response", exception);
        }
    }

    private void requireApiKey() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new RailRadarSyncException(HttpStatus.SERVICE_UNAVAILABLE,
                    "RailRadar sync is not configured with an API key");
        }
    }

    private <T> T requireData(RailRadarApiEnvelope<T> response, String operation) {
        if (response == null || !response.success() || response.data() == null) {
            throw new RailRadarSyncException(HttpStatus.BAD_GATEWAY,
                    "RailRadar " + operation + " returned an unsuccessful or empty response");
        }
        return response.data();
    }

    private RailRadarSyncException upstreamStatus(RestClientResponseException exception) {
        int statusCode = exception.getStatusCode().value();
        if (statusCode == HttpStatus.TOO_MANY_REQUESTS.value()) {
            String retryAfter = exception.getResponseHeaders() == null
                    ? null : exception.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            if (retryAfter != null) {
                String safeRetryAfter = retryAfter.replaceAll("[\\r\\n\\p{Cntrl}]", " ");
                if (safeRetryAfter.length() > 100) {
                    safeRetryAfter = safeRetryAfter.substring(0, 100);
                }
                logger.warn("RailRadar returned HTTP 429 during a sync request; Retry-After: {}",
                        safeRetryAfter);
            } else {
                logger.warn("RailRadar returned HTTP 429 during a sync request without Retry-After");
            }
        } else {
            logger.warn("RailRadar returned HTTP status {} during a sync request", statusCode);
        }
        return new RailRadarSyncException(HttpStatus.BAD_GATEWAY,
                "RailRadar rejected or could not complete the sync request (HTTP " + statusCode + ")");
    }

    private boolean isTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
