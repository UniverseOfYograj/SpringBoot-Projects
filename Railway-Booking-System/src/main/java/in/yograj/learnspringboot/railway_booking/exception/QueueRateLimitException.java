package in.yograj.learnspringboot.railway_booking.exception;

public class QueueRateLimitException extends RuntimeException {
    public QueueRateLimitException(String message) {
        super(message);
    }
}
