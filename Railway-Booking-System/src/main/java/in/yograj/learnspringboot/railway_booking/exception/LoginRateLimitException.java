package in.yograj.learnspringboot.railway_booking.exception;

public class LoginRateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public LoginRateLimitException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
