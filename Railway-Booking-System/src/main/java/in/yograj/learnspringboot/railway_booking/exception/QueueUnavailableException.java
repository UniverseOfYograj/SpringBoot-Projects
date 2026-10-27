package in.yograj.learnspringboot.railway_booking.exception;

public class QueueUnavailableException extends RuntimeException {
    public QueueUnavailableException(String message) {
        super(message);
    }

    public QueueUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
