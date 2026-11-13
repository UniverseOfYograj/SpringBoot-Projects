package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.config.RedisCacheConfig;
import in.yograj.learnspringboot.railway_booking.dto.TrainDetailResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainRouteResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainSearchResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainStopResponseDTO;
import in.yograj.learnspringboot.railway_booking.entity.Train;
import in.yograj.learnspringboot.railway_booking.entity.TrainRoute;
import in.yograj.learnspringboot.railway_booking.entity.TrainStop;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.repository.TrainRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainRouteRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainStopRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Transactional(readOnly = true)
public class TrainSearchServiceImpl implements TrainSearchService {

    private static final Pattern ROUTE_PATTERN = Pattern.compile(
            "^([A-Za-z0-9]{2,6})\\s*(?:->|→|-|to)\\s*([A-Za-z0-9]{2,6})$",
            Pattern.CASE_INSENSITIVE
    );

    private final TrainStopRepository trainStopRepository;
    private final TrainRepository trainRepository;
    private final TrainRouteRepository trainRouteRepository;

    public TrainSearchServiceImpl(TrainStopRepository trainStopRepository) {
        this(trainStopRepository, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TrainSearchServiceImpl(TrainStopRepository trainStopRepository,
                                  TrainRepository trainRepository,
                                  TrainRouteRepository trainRouteRepository) {
        this.trainStopRepository = trainStopRepository;
        this.trainRepository = trainRepository;
        this.trainRouteRepository = trainRouteRepository;
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

    @Override
    public List<TrainSearchResponseDTO> searchTrainsUnified(String query, String fromCode, String toCode, int page, int size) {
        // Case 1: from and to station codes provided
        if (fromCode != null && !fromCode.isBlank() && toCode != null && !toCode.isBlank()) {
            List<TrainSearchResponseDTO> stationResults = searchTrains(fromCode, toCode);
            if (query != null && !query.isBlank()) {
                String qLower = query.trim().toLowerCase();
                return stationResults.stream()
                        .filter(t -> (t.trainNumber() != null && t.trainNumber().toLowerCase().contains(qLower))
                                || (t.trainName() != null && t.trainName().toLowerCase().contains(qLower)))
                        .toList();
            }
            return stationResults;
        }

        // Case 2: query provided
        if (query != null && !query.isBlank()) {
            String trimmedQuery = query.trim();

            // Check if query is a station pair e.g. "STA -> KTE" or "HWH - NDLS"
            Matcher matcher = ROUTE_PATTERN.matcher(trimmedQuery);
            if (matcher.matches()) {
                String parsedFrom = matcher.group(1).trim().toUpperCase();
                String parsedTo = matcher.group(2).trim().toUpperCase();
                return searchTrains(parsedFrom, parsedTo);
            }

            // Search by train number or train name
            int safePage = Math.max(0, page);
            int safeSize = Math.min(Math.max(1, size), 50);
            List<Train> matchingTrains = trainRepository.searchByNumberOrName(trimmedQuery, PageRequest.of(safePage, safeSize));

            List<TrainSearchResponseDTO> dtos = new ArrayList<>();
            for (Train train : matchingTrains) {
                List<TrainRoute> routes = trainRouteRepository.findByTrain_TrainIdOrderByRouteName(train.getTrainId());
                if (!routes.isEmpty()) {
                    TrainRoute primaryRoute = routes.get(0);
                    List<TrainStop> stops = trainStopRepository.findByTrainRoute_TrainRouteIdOrderByStopSequence(primaryRoute.getTrainRouteId());
                    if (stops.size() >= 2) {
                        TrainStop first = stops.get(0);
                        TrainStop last = stops.get(stops.size() - 1);
                        dtos.add(new TrainSearchResponseDTO(
                                train.getTrainId(),
                                train.getTrainNumber(),
                                train.getTrainName(),
                                first.getStation() != null ? first.getStation().getStationCode() : "",
                                first.getStation() != null ? first.getStation().getStationName() : "",
                                first.getArrivalTime(),
                                first.getDepartureTime(),
                                first.getDayOffset(),
                                last.getStation() != null ? last.getStation().getStationCode() : "",
                                last.getStation() != null ? last.getStation().getStationName() : "",
                                last.getArrivalTime(),
                                last.getDepartureTime(),
                                last.getDayOffset()
                        ));
                        continue;
                    }
                }
                // Fallback if route stops are empty
                dtos.add(new TrainSearchResponseDTO(
                        train.getTrainId(),
                        train.getTrainNumber(),
                        train.getTrainName(),
                        "", "", null, null, 0,
                        "", "", null, null, 0
                ));
            }
            return dtos;
        }

        return List.of();
    }

    @Override
    public TrainDetailResponseDTO getTrainByNumber(String trainNumber) {
        if (trainNumber == null || trainNumber.isBlank()) {
            throw new ResourceNotFoundException("Train number is required");
        }
        Train train = trainRepository.findByTrainNumber(trainNumber.trim().toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("Train not found for number: " + trainNumber));

        List<TrainRoute> routes = trainRouteRepository.findByTrain_TrainIdOrderByRouteName(train.getTrainId());
        List<TrainRouteResponseDTO> routeDTOs = routes.stream().map(route -> {
            List<TrainStop> stops = trainStopRepository.findByTrainRoute_TrainRouteIdOrderByStopSequence(route.getTrainRouteId());
            List<TrainStopResponseDTO> stopDTOs = stops.stream().map(s -> new TrainStopResponseDTO(
                    s.getTrainStopId(),
                    s.getStation() != null ? s.getStation().getStationId() : null,
                    s.getStation() != null ? s.getStation().getStationCode() : null,
                    s.getStation() != null ? s.getStation().getStationName() : null,
                    s.getStopSequence(),
                    s.getArrivalTime(),
                    s.getDepartureTime(),
                    s.getDayOffset()
            )).toList();
            return new TrainRouteResponseDTO(route.getTrainRouteId(), train.getTrainId(), route.getRouteName(), stopDTOs);
        }).toList();

        return new TrainDetailResponseDTO(
                train.getTrainId(),
                train.getTrainNumber(),
                train.getTrainName(),
                train.getStatus(),
                train.getOperatingDays(),
                train.getClassProfile(),
                routeDTOs
        );
    }
}
