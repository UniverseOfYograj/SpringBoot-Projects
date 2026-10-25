package in.yograj.learnspringboot.railway_booking.integration.railradar.exception;

import org.springframework.http.HttpStatus;

public class RailRadarSyncException extends RuntimeException {

    private final HttpStatus status;

    public RailRadarSyncException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public RailRadarSyncException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
