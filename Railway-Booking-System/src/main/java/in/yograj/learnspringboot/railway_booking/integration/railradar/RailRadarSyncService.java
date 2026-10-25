package in.yograj.learnspringboot.railway_booking.integration.railradar;

import in.yograj.learnspringboot.railway_booking.entity.Station;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.RailRadarStationDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.RailRadarTrainDetailsDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.StationSyncRequestDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.StationSyncResponseDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.TrainSyncResponseDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.exception.RailRadarSyncException;
import in.yograj.learnspringboot.railway_booking.integration.railradar.mapper.RailRadarMapper;
import in.yograj.learnspringboot.railway_booking.integration.railradar.mapper.RailRadarTrainImport;
import in.yograj.learnspringboot.railway_booking.repository.StationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RailRadarSyncService {

    private static final Logger logger = LoggerFactory.getLogger(RailRadarSyncService.class);
    private static final int DEFAULT_STATION_LIMIT = 10;

    private final RailRadarClient railRadarClient;
    private final RailRadarMapper mapper;
    private final RailRadarPersistenceService persistenceService;
    private final StationRepository stationRepository;

    public RailRadarSyncService(
            RailRadarClient railRadarClient,
            RailRadarMapper mapper,
            RailRadarPersistenceService persistenceService,
            StationRepository stationRepository) {
        this.railRadarClient = railRadarClient;
        this.mapper = mapper;
        this.persistenceService = persistenceService;
        this.stationRepository = stationRepository;
    }

    public StationSyncResponseDTO syncStations(StationSyncRequestDTO request) {
        if (request == null) {
            throw new RailRadarSyncException(HttpStatus.BAD_REQUEST, "Station sync request is required");
        }
        int limit = request.limit() == null ? DEFAULT_STATION_LIMIT : request.limit();
        if (!List.of(5, 10, 20, 50).contains(limit)) {
            throw new RailRadarSyncException(HttpStatus.BAD_REQUEST,
                    "Station limit must be one of 5, 10, 20, or 50");
        }
        logger.info("Starting RailRadar station sync");
        List<RailRadarStationDTO> fetched = railRadarClient.searchStations(request.query().trim(), limit);
        logger.info("Fetched {} RailRadar stations", fetched.size());
        var mapped = mapper.mapStations(fetched);
        StationSyncResponseDTO result = persistenceService.persistStations(mapped);
        logger.info("Station sync completed: inserted {}, updated {}, unchanged {}, skipped {}, unresolved {}",
                result.inserted(), result.updated(), result.unchanged(), result.skipped(),
                result.unresolved().size());
        return result;
    }

    public TrainSyncResponseDTO syncTrain(String trainNumber) {
        if (trainNumber == null || !trainNumber.matches("\\d{5}")) {
            throw new RailRadarSyncException(HttpStatus.BAD_REQUEST,
                    "Train number must contain exactly five digits");
        }
        logger.info("Starting RailRadar train sync");
        RailRadarTrainDetailsDTO fetched = railRadarClient.getTrainDetails(trainNumber);
        RailRadarTrainImport mapped = mapper.mapTrain(trainNumber, fetched);
        logger.info("Fetched timetable with {} stops", mapped.stops().size());
        List<Station> missingStations = mapped.stops().stream()
                .map(RailRadarTrainImport.Stop::stationCode)
                .distinct()
                .filter(code -> stationRepository.findByStationCode(code).isEmpty())
                .map(this::searchStationByCode)
                .toList();
        TrainSyncResponseDTO result = persistenceService.persistTrain(mapped, missingStations);
        logger.info("Train sync completed: train {}, stops inserted {}, route reused {}",
                result.trainNumber(), result.stopsInserted(), result.routeReused());
        return result;
    }

    private Station searchStationByCode(String stationCode) {
        RailRadarStationDTO match = railRadarClient.searchStations(stationCode, DEFAULT_STATION_LIMIT).stream()
                .filter(station -> station.code() != null
                        && station.code().trim().equalsIgnoreCase(stationCode))
                .findFirst()
                .orElseThrow(() -> new RailRadarSyncException(HttpStatus.BAD_GATEWAY,
                        "RailRadar station search returned no exact match for route station code: "
                                + stationCode));
        if (match.name() == null || match.name().isBlank()
                || match.city() == null || match.city().isBlank()) {
            throw new RailRadarSyncException(HttpStatus.BAD_GATEWAY,
                    "RailRadar station search returned incomplete name or city for route station code: "
                            + stationCode);
        }
        Station station = mapper.mapStations(List.of(match)).stations().stream()
                .findFirst()
                .orElseThrow(() -> new RailRadarSyncException(HttpStatus.BAD_GATEWAY,
                        "RailRadar station search returned invalid station data for route station code: "
                                + stationCode));
        station.setStationCode(stationCode);
        return station;
    }
}
