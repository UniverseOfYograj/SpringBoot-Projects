package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.dto.CoachTopologyDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainTopologyDTO;

public interface TrainTopologyService {

    /**
     * Retrieve static coach and seat layout for a train.
     * Served from Redis cache when available with 24-hour TTL;
     * falls back to PostgreSQL on cache miss or cache outage.
     */
    TrainTopologyDTO getTrainTopology(Long trainId);

    /**
     * Retrieve static seat layout for a specific coach.
     * Served from Redis cache when available with 24-hour TTL;
     * falls back to PostgreSQL on cache miss or cache outage.
     */
    CoachTopologyDTO getCoachTopology(Long coachId);

    /**
     * Targeted eviction of train topology from cache.
     */
    void evictTrainTopology(Long trainId);

    /**
     * Targeted eviction of coach topology from cache.
     */
    void evictCoachTopology(Long coachId);

    /**
     * Evict all cached train and coach topology entries.
     */
    void evictAllTopology();
}
