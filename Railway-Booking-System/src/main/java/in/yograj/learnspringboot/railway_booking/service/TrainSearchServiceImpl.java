package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.dto.TrainSearchResponseDTO;
import in.yograj.learnspringboot.railway_booking.entity.TrainStop;
import in.yograj.learnspringboot.railway_booking.repository.TrainStopRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TrainSearchServiceImpl implements TrainSearchService {

    private final TrainStopRepository trainStopRepository;

    public TrainSearchServiceImpl(TrainStopRepository trainStopRepository) {
        this.trainStopRepository = trainStopRepository;
    }

    @Override
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
                .toList();
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