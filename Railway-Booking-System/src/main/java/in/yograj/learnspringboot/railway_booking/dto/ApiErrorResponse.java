package in.yograj.learnspringboot.railway_booking.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String correlationId,
        Map<String, String> fieldErrors
) {
    public ApiErrorResponse {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (fieldErrors != null) {
            fieldErrors = Collections.unmodifiableMap(fieldErrors);
        }
    }

    public static ApiErrorResponse of(int status, String error, String message, String path) {
        return new ApiErrorResponse(Instant.now(), status, error, message, path, MDC.get("correlationId"), null);
    }

    public static ApiErrorResponse of(int status, String error, String message, String path, Map<String, String> fieldErrors) {
        return new ApiErrorResponse(Instant.now(), status, error, message, path, MDC.get("correlationId"), fieldErrors);
    }

    public static ApiErrorResponse of(int status, String error, String message, String path, String correlationId, Map<String, String> fieldErrors) {
        return new ApiErrorResponse(Instant.now(), status, error, message, path, correlationId, fieldErrors);
    }
}
