package in.yograj.learnspringboot.railway_booking.service;

public enum ReapResult {
    EXPIRED_BOOKING_AND_ALLOCATION,
    EXPIRED_ALLOCATION,
    LEGACY_HOLD_NORMALIZED,
    INTEGRITY_ANOMALY,
    SKIPPED_NOT_HOLD,
    SKIPPED_NOT_EXPIRED,
    SKIPPED_CONFIRMED,
    SKIPPED_NOT_FOUND,
    FAILED
}
