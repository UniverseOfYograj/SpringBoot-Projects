package in.yograj.learnspringboot.railway_booking.exception;

public class InvalidAdmissionPassException extends RuntimeException {
    public InvalidAdmissionPassException(String message) {
        super(message);
    }

    public InvalidAdmissionPassException(String message, Throwable cause) {
        super(message, cause);
    }
}
