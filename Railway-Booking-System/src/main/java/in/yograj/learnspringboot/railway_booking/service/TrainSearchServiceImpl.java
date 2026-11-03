package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.config.RedisCacheConfig;
import in.yograj.learnspringboot.railway_booking.dto.TrainSearchResponseDTO;
import in.yograj.learnspringboot.railway_booking.entity.TrainStop;
import in.yograj.learnspringboot.railway_booking.repository.TrainStopRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

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

        List<Object[]> rows =
                trainStopRepository.findTrainStopsBetweenStations(
                        normalizedFromCode,
                        normalizedToCode
                );

        return rows.stream()
                .map(this::toResponse)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    private TrainSearchResponseDTO toResponse(Object[] row) {

        TrainStop fromStop = (TrainStop) row[0];
        TrainStop toStop = (TrainStop) row[1];

        return new TrainSearchResponseDTO(
                fromStop.getTrainRoute().getTrain().getTrainId(),
                fromStop.getTrainRoute().getTrain().getTrainNumber(),
                fromStop.getTrainRoute().getTrain().getTrainName(),

                fromStop.getStation().getStationCode(),
                fromStop.getStation().getStationName(),
                fromStop.getArrivalTime(),
                fromStop.getDepartureTime(),
                fromStop.getDayOffset(),

                toStop.getStation().getStationCode(),
                toStop.getStation().getStationName(),
                toStop.getArrivalTime(),
                toStop.getDepartureTime(),
                toStop.getDayOffset()
        );
    }
}
