package in.yograj.learnspringboot.railway_booking.integration.railradar;

import in.yograj.learnspringboot.railway_booking.entity.Station;
import in.yograj.learnspringboot.railway_booking.entity.Train;
import in.yograj.learnspringboot.railway_booking.entity.TrainRoute;
import in.yograj.learnspringboot.railway_booking.entity.TrainStop;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.StationSyncResponseDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.TrainSyncResponseDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.UnresolvedStationDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.exception.RailRadarSyncException;
import in.yograj.learnspringboot.railway_booking.integration.railradar.mapper.RailRadarStationMapping;
import in.yograj.learnspringboot.railway_booking.integration.railradar.mapper.RailRadarTrainImport;
import in.yograj.learnspringboot.railway_booking.repository.StationRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainRouteRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainStopRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class RailRadarPersistenceService {

    private static final String ROUTE_NAME = "RailRadar timetable";

    private final StationRepository stationRepository;
    private final TrainRepository trainRepository;
    private final TrainRouteRepository trainRouteRepository;
    private final TrainStopRepository trainStopRepository;

    public RailRadarPersistenceService(
            StationRepository stationRepository,
            TrainRepository trainRepository,
            TrainRouteRepository trainRouteRepository,
            TrainStopRepository trainStopRepository) {
        this.stationRepository = stationRepository;
        this.trainRepository = trainRepository;
        this.trainRouteRepository = trainRouteRepository;
        this.trainStopRepository = trainStopRepository;
    }

    @Transactional
    public StationSyncResponseDTO persistStations(RailRadarStationMapping mapping) {
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        int skipped = mapping.skipped();
        List<UnresolvedStationDTO> unresolved = new ArrayList<>(mapping.unresolved());

        for (Station imported : mapping.stations()) {
            Station station = stationRepository.findByStationCode(imported.getStationCode()).orElse(null);
            String stationName = firstNonBlank(imported.getStationName(),
                    station == null ? null : station.getStationName());
            String city = firstNonBlank(imported.getCity(), station == null ? null : station.getCity());
            if (stationName == null || city == null) {
                skipped++;
                String reason = stationName == null
                        ? "Station name is missing from RailRadar and local PostgreSQL"
                        : "Station city is missing from RailRadar and local PostgreSQL";
                unresolved.add(new UnresolvedStationDTO(imported.getStationCode(), reason));
                continue;
            }

            if (station == null) {
                station = new Station();
                station.setStationCode(imported.getStationCode());
                station.setStationName(stationName);
                station.setCity(city);
                stationRepository.save(station);
                inserted++;
                continue;
            }
            boolean changed = !Objects.equals(station.getStationName(), stationName)
                    || !Objects.equals(station.getCity(), city);
            if (changed) {
                station.setStationName(stationName);
                station.setCity(city);
                stationRepository.save(station);
                updated++;
            } else {
                unchanged++;
            }
        }
        return new StationSyncResponseDTO(
                mapping.fetched(), inserted, updated, unchanged, skipped, List.copyOf(unresolved));
    }

    private String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return null;
    }

    @Transactional
    public TrainSyncResponseDTO persistTrain(
            RailRadarTrainImport importedTrain,
            List<Station> stationCandidates) {
        Train train = trainRepository.findByTrainNumber(importedTrain.trainNumber()).orElse(null);
        List<TrainRoute> routes = train == null ? List.of()
                : trainRouteRepository.findByTrain_TrainIdOrderByRouteName(train.getTrainId());
        TrainRoute route = routes.stream()
                .filter(candidate -> ROUTE_NAME.equals(candidate.getRouteName()))
                .findFirst()
                .orElse(null);
        Map<String, Station> candidatesByCode = new HashMap<>();
        for (Station candidate : stationCandidates) {
            candidatesByCode.put(candidate.getStationCode(), candidate);
        }
        Map<String, Station> stationsByCode = new HashMap<>();
        List<Station> stations = new ArrayList<>(importedTrain.stops().size());
        for (RailRadarTrainImport.Stop stop : importedTrain.stops()) {
            Station station = stationsByCode.get(stop.stationCode());
            if (station == null) {
                station = stationRepository.findByStationCode(stop.stationCode()).orElse(null);
                if (station == null) {
                    station = candidatesByCode.get(stop.stationCode());
                    if (station == null) {
                        throw new RailRadarSyncException(HttpStatus.BAD_GATEWAY,
                                "No station data is available for route station code: " + stop.stationCode());
                    }
                    if (station.getStationName() == null || station.getStationName().isBlank()
                            || station.getCity() == null || station.getCity().isBlank()) {
                        throw new RailRadarSyncException(HttpStatus.BAD_GATEWAY,
                                "Station name or city is missing for route station code: " + stop.stationCode());
                    }
                    station.setStationCode(stop.stationCode());
                    station = stationRepository.save(station);
                }
                stationsByCode.put(stop.stationCode(), station);
            }
            stations.add(station);
        }

        if (route != null) {
            List<TrainStop> existingStops = trainStopRepository
                    .findByTrainRoute_TrainRouteIdOrderByStopSequence(route.getTrainRouteId());
            if (!sameRoute(existingStops, importedTrain, stations)) {
                throw new RailRadarSyncException(HttpStatus.CONFLICT,
                        "The RailRadar timetable differs from the saved route. Automatic replacement is blocked "
                                + "to preserve existing dated-run references.");
            }
        }

        boolean trainInserted = train == null;
        boolean trainUpdated = false;
        if (train == null) {
            train = new Train();
            train.setTrainNumber(importedTrain.trainNumber());
            train.setTrainName(importedTrain.trainName());
            train.setStatus("ACTIVE");
            train = trainRepository.save(train);
        } else if (!Objects.equals(train.getTrainName(), importedTrain.trainName())) {
            train.setTrainName(importedTrain.trainName());
            train = trainRepository.save(train);
            trainUpdated = true;
        }

        int stopsInserted = 0;
        boolean routeReused = route != null;
        if (route == null) {
            route = new TrainRoute();
            route.setTrain(train);
            route.setRouteName(ROUTE_NAME);
            route = trainRouteRepository.save(route);
            List<TrainStop> stops = new ArrayList<>(importedTrain.stops().size());
            for (int i = 0; i < importedTrain.stops().size(); i++) {
                RailRadarTrainImport.Stop source = importedTrain.stops().get(i);
                TrainStop stop = new TrainStop();
                stop.setTrainRoute(route);
                stop.setStation(stations.get(i));
                stop.setStopSequence(source.sequence());
                stop.setArrivalTime(source.arrivalTime());
                stop.setDepartureTime(source.departureTime());
                stop.setDayOffset(source.dayOffset());
                stops.add(stop);
            }
            stopsInserted = trainStopRepository.saveAll(stops).size();
        }

        return new TrainSyncResponseDTO(
                train.getTrainNumber(),
                train.getTrainId(),
                route.getRouteName(),
                trainInserted ? 1 : 0,
                trainUpdated ? 1 : 0,
                stopsInserted,
                routeReused);
    }

    private boolean sameRoute(
            List<TrainStop> existingStops,
            RailRadarTrainImport importedTrain,
            List<Station> stations) {
        if (existingStops.size() != importedTrain.stops().size()) {
            return false;
        }
        for (int index = 0; index < existingStops.size(); index++) {
            TrainStop existing = existingStops.get(index);
            RailRadarTrainImport.Stop imported = importedTrain.stops().get(index);
            if (!Objects.equals(existing.getStopSequence(), imported.sequence())
                    || !Objects.equals(existing.getStation().getStationId(), stations.get(index).getStationId())
                    || !Objects.equals(existing.getArrivalTime(), imported.arrivalTime())
                    || !Objects.equals(existing.getDepartureTime(), imported.departureTime())
                    || !Objects.equals(existing.getDayOffset(), imported.dayOffset())) {
                return false;
            }
        }
        return true;
    }
}
