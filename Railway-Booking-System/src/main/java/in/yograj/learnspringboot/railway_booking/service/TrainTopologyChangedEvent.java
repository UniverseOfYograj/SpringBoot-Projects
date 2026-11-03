package in.yograj.learnspringboot.railway_booking.service;

/**
 * Event published when coach or seat topology changes.
 * Consumed by {@link TrainTopologyServiceImpl} after database transaction commit
 * to perform targeted cache eviction.
 */
public record TrainTopologyChangedEvent(Long trainId, Long coachId) {
}
