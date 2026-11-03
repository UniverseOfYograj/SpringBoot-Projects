package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.config.RedisCacheConfig;
import in.yograj.learnspringboot.railway_booking.dto.CoachTopologyDTO;
import in.yograj.learnspringboot.railway_booking.dto.SeatTopologyDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainTopologyDTO;
import in.yograj.learnspringboot.railway_booking.entity.Coach;
import in.yograj.learnspringboot.railway_booking.entity.Seat;
import in.yograj.learnspringboot.railway_booking.entity.Train;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.repository.CoachRepository;
import in.yograj.learnspringboot.railway_booking.repository.SeatRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for querying and caching static train coach and seat topology.
 * Serves immutable layout metadata from Redis to avoid repeated PostgreSQL joins.
 * Dynamic seat inventory statuses, holds, and locks are strictly not handled here.
 */
@Service
public class TrainTopologyServiceImpl implements TrainTopologyService {

    private static final Logger log = LoggerFactory.getLogger(TrainTopologyServiceImpl.class);

    private final TrainRepository trainRepository;
    private final CoachRepository coachRepository;
    private final SeatRepository seatRepository;
    private final CacheManager cacheManager;

    public TrainTopologyServiceImpl(TrainRepository trainRepository,
                                   CoachRepository coachRepository,
                                   SeatRepository seatRepository,
                                   CacheManager cacheManager) {
        this.trainRepository = trainRepository;
        this.coachRepository = coachRepository;
        this.seatRepository = seatRepository;
        this.cacheManager = cacheManager;
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = RedisCacheConfig.TRAIN_TOPOLOGY_CACHE, key = "'train:' + #trainId", unless = "#result == null")
    public TrainTopologyDTO getTrainTopology(Long trainId) {
        Train train = trainRepository.findById(trainId)
                .orElseThrow(() -> new ResourceNotFoundException("Train not found with id: " + trainId));

        List<Coach> coaches = coachRepository.findByTrain_TrainId(trainId);
        if (coaches.isEmpty()) {
            return new TrainTopologyDTO(train.getTrainId(), train.getTrainNumber(), train.getTrainName(), 0, 0, List.of());
        }

        List<Long> coachIds = coaches.stream().map(Coach::getCoachId).toList();
        List<Seat> allSeats = seatRepository.findByCoach_CoachIdIn(coachIds);
        Map<Long, List<Seat>> seatsByCoachId = allSeats.stream()
                .collect(Collectors.groupingBy(s -> s.getCoach().getCoachId()));

        List<CoachTopologyDTO> coachDTOs = coaches.stream().map(coach -> {
            List<Seat> coachSeats = seatsByCoachId.getOrDefault(coach.getCoachId(), List.of());
            List<SeatTopologyDTO> seatDTOs = coachSeats.stream()
                    .map(seat -> new SeatTopologyDTO(
                            seat.getSeatId(),
                            coach.getCoachId(),
                            seat.getSeatNumber(),
                            seat.getSeatType(),
                            seat.getSeatType()
                    ))
                    .toList();
            return new CoachTopologyDTO(
                    coach.getCoachId(),
                    train.getTrainId(),
                    coach.getCoachNumber(),
                    coach.getCoachType(),
                    coach.getSeatCapacity(),
                    seatDTOs
            );
        }).toList();

        int totalSeats = coachDTOs.stream().mapToInt(c -> c.seats().size()).sum();

        return new TrainTopologyDTO(
                train.getTrainId(),
                train.getTrainNumber(),
                train.getTrainName(),
                coachDTOs.size(),
                totalSeats,
                coachDTOs
        );
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = RedisCacheConfig.TRAIN_TOPOLOGY_CACHE, key = "'coach:' + #coachId", unless = "#result == null")
    public CoachTopologyDTO getCoachTopology(Long coachId) {
        Coach coach = coachRepository.findById(coachId)
                .orElseThrow(() -> new ResourceNotFoundException("Coach not found with id: " + coachId));

        List<Seat> seats = seatRepository.findByCoach_CoachId(coachId);
        List<SeatTopologyDTO> seatDTOs = seats.stream()
                .map(seat -> new SeatTopologyDTO(
                        seat.getSeatId(),
                        coach.getCoachId(),
                        seat.getSeatNumber(),
                        seat.getSeatType(),
                        seat.getSeatType()
                ))
                .toList();

        return new CoachTopologyDTO(
                coach.getCoachId(),
                coach.getTrain().getTrainId(),
                coach.getCoachNumber(),
                coach.getCoachType(),
                coach.getSeatCapacity(),
                seatDTOs
        );
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.TRAIN_TOPOLOGY_CACHE, key = "'train:' + #trainId")
    public void evictTrainTopology(Long trainId) {
        Cache cache = cacheManager.getCache(RedisCacheConfig.TRAIN_TOPOLOGY_CACHE);
        if (cache != null) {
            cache.evict("train:" + trainId);
        }
        log.info("Evicted train topology cache for trainId={}", trainId);
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.TRAIN_TOPOLOGY_CACHE, key = "'coach:' + #coachId")
    public void evictCoachTopology(Long coachId) {
        Cache cache = cacheManager.getCache(RedisCacheConfig.TRAIN_TOPOLOGY_CACHE);
        if (cache != null) {
            cache.evict("coach:" + coachId);
        }
        log.info("Evicted coach topology cache for coachId={}", coachId);
    }

    @Override
    @CacheEvict(value = RedisCacheConfig.TRAIN_TOPOLOGY_CACHE, allEntries = true)
    public void evictAllTopology() {
        Cache cache = cacheManager.getCache(RedisCacheConfig.TRAIN_TOPOLOGY_CACHE);
        if (cache != null) {
            cache.clear();
        }
        log.info("Evicted all entries in train topology cache");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleTopologyChanged(TrainTopologyChangedEvent event) {
        Cache cache = cacheManager.getCache(RedisCacheConfig.TRAIN_TOPOLOGY_CACHE);
        if (cache != null) {
            if (event.trainId() != null) {
                cache.evict("train:" + event.trainId());
                log.info("Post-commit evicted train topology cache for trainId={}", event.trainId());
            }
            if (event.coachId() != null) {
                cache.evict("coach:" + event.coachId());
                log.info("Post-commit evicted coach topology cache for coachId={}", event.coachId());
            }
        }
    }
}
