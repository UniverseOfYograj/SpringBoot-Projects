package in.yograj.learnspringboot.railway_booking.entity;

public enum OutboxEventStatus {
    PENDING,
    CLAIMED,
    PUBLISHED,
    FAILED,
    DEAD
}
