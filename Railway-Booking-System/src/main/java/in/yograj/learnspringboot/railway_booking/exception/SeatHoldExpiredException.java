package in.yograj.learnspringboot.railway_booking.exception;

public class SeatHoldExpiredException extends BadRequestException {
    public SeatHoldExpiredException(String message) {
        super(message);
    }
}
