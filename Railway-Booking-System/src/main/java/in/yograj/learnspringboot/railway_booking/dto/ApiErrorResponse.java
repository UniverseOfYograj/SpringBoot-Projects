package in.yograj.learnspringboot.railway_booking.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
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
        return new ApiErrorResponse(Instant.now(), status, error, message, path, null);
    }

    public static ApiErrorResponse of(int status, String error, String message, String path, Map<String, String> fieldErrors) {
        return new ApiErrorResponse(Instant.now(), status, error, message, path, fieldErrors);
    }
}
