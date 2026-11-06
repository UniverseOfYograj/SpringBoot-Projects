package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.config.RedisCacheConfig;
import in.yograj.learnspringboot.railway_booking.dto.TrainSearchResponseDTO;
import in.yograj.learnspringboot.railway_booking.repository.TrainStopRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TrainSearchServiceImpl implements TrainSearchService {

    private final TrainStopRepository trainStopRepository;

    public TrainSearchServiceImpl(TrainStopRepository trainStopRepository) {
        this.trainStopRepository = trainStopRepository;
    }

    @Override
    @Cacheable(
            value = RedisCacheConfig.TRAIN_SEARCH_CACHE,
            key = "#fromCode.trim().toUpperCase() + '_' + #toCode.trim().toUpperCase()",
            unless = "#result == null"
    )
    public List<TrainSearchResponseDTO> searchTrains(String fromCode, String toCode) {

        String normalizedFromCode = fromCode.trim().toUpperCase();
        String normalizedToCode = toCode.trim().toUpperCase();

        List<TrainSearchResponseDTO> results =
                trainStopRepository.findTrainStopsBetweenStations(
                        normalizedFromCode,
                        normalizedToCode
                );

        return new ArrayList<>(results);
    }
}
