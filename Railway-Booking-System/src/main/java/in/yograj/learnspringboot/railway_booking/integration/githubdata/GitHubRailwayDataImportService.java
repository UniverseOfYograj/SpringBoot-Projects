package in.yograj.learnspringboot.railway_booking.integration.githubdata;

import in.yograj.learnspringboot.railway_booking.entity.Station;

import in.yograj.learnspringboot.railway_booking.entity.Train;

import in.yograj.learnspringboot.railway_booking.entity.TrainRoute;

import in.yograj.learnspringboot.railway_booking.entity.TrainStop;

import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;

import in.yograj.learnspringboot.railway_booking.repository.StationRepository;

import in.yograj.learnspringboot.railway_booking.repository.TrainRepository;

import in.yograj.learnspringboot.railway_booking.repository.TrainRouteRepository;

import in.yograj.learnspringboot.railway_booking.repository.TrainStopRepository;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import org.springframework.http.MediaType;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.client.RestClient;

import org.springframework.web.client.RestClientException;

import tools.jackson.core.JacksonException;

import tools.jackson.core.JsonParser;

import tools.jackson.core.JsonToken;

import tools.jackson.databind.DeserializationFeature;

import tools.jackson.databind.JsonNode;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalTime;

import java.time.format.DateTimeParseException;

import java.util.ArrayList;

import java.util.HashMap;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Locale;

import java.util.Map;

import java.util.Objects;

import java.util.function.Consumer;

@Service

public class GitHubRailwayDataImportService {

    private static final Logger logger = LoggerFactory.getLogger(GitHubRailwayDataImportService.class);

    private static final String DATASET_BASE_URL =

            "https://raw.githubusercontent.com/prasenjit-27/Indian-Railway-Data/"

                    + "1dc0ddbc6a06b09ef69d4825ff3127c1f29640fa/";

    private static final String ROUTE_NAME = "GitHub railway dataset";

    private final RestClient restClient;

    private final ObjectMapper objectMapper;

    private final StationRepository stationRepository;

    private final TrainRepository trainRepository;

    private final TrainRouteRepository trainRouteRepository;

    private final TrainStopRepository trainStopRepository;

    public GitHubRailwayDataImportService(

            RestClient.Builder restClientBuilder,

            ObjectMapper objectMapper,

            StationRepository stationRepository,

            TrainRepository trainRepository,

            TrainRouteRepository trainRouteRepository,

            TrainStopRepository trainStopRepository) {

        this.restClient = restClientBuilder.baseUrl(DATASET_BASE_URL).build();

        this.objectMapper = objectMapper;

        this.stationRepository = stationRepository;

        this.trainRepository = trainRepository;

        this.trainRouteRepository = trainRouteRepository;

        this.trainStopRepository = trainStopRepository;

    }

    @Transactional
    public ImportResult importData(int startFrom, int maxTrains) {
        if (startFrom < 1) {
            throw new BadRequestException("startFrom must be at least 1");
        }

        if (maxTrains < startFrom) {
            throw new BadRequestException("maxTrains must be greater than or equal to startFrom");
        }

        ImportCounts counts = new ImportCounts();
        Map<String, Station> stations = importStations(counts);

        readArray(
                "trains.json",
                startFrom,
                maxTrains,
                train -> importTrain(train, stations, counts)
        );

        return new ImportResult(
                counts.stationsProcessed,
                counts.stationsInserted,
                counts.stationsUpdated,
                counts.stationsUnchanged,
                counts.trainsInserted,
                counts.trainsUpdated,
                counts.routesInserted,
                counts.routesReused,
                counts.stopsInserted,
                counts.trainsImported
        );
    }

    private Map<String, Station> importStations(ImportCounts counts) {

        Map<String, Station> stationsByCode = new HashMap<>();

        for (Station station : stationRepository.findAll()) {

            stationsByCode.put(normalizeCode(station.getStationCode()), station);

        }

        Map<String, Station> datasetByCode = new LinkedHashMap<>();

        readArray("stations.json", Integer.MAX_VALUE, row -> {

           String code = normalizeCode(requiredText(row, "code"));

String name = optionalText(row, "name");

if (name == null) {

    // Dataset me ek invalid station record hai (code=P), isko skip kar rahe hain.

    return;

}

name = bounded(name, 120, "station name");

String state = boundedOptional(optionalText(row, "state"), 120, "station state");

            if (code.length() > 20) {

                throw new BadRequestException("Station code exceeds the database column length: " + code);

            }

            Station candidate = new Station();

            candidate.setStationCode(code);

            candidate.setStationName(name);

            candidate.setState(state);

            Station duplicate = datasetByCode.putIfAbsent(code, candidate);

            if (duplicate != null

                    && (!Objects.equals(duplicate.getStationName(), candidate.getStationName())

                    || !Objects.equals(duplicate.getState(), candidate.getState()))) {

                throw new BadRequestException("Dataset contains conflicting station records for code: " + code);

            }

        });

        counts.stationsProcessed = datasetByCode.size();

        List<Station> changedStations = new ArrayList<>();

        for (Station imported : datasetByCode.values()) {

            String code = imported.getStationCode();

            Station existing = stationsByCode.get(code);

            if (existing == null) {

                changedStations.add(imported);

                stationsByCode.put(code, imported);

                counts.stationsInserted++;

            } else if (!Objects.equals(existing.getStationName(), imported.getStationName())

                    || (imported.getState() != null

                    && !Objects.equals(existing.getState(), imported.getState()))) {

                existing.setStationName(imported.getStationName());

                if (imported.getState() != null) {

                    existing.setState(imported.getState());

                }

                changedStations.add(existing);

                counts.stationsUpdated++;

            } else {

                counts.stationsUnchanged++;

            }

        }

        if (!changedStations.isEmpty()) {

            for (Station station : stationRepository.saveAll(changedStations)) {

                stationsByCode.put(normalizeCode(station.getStationCode()), station);

            }

        }

        return stationsByCode;

    }

    private void importTrain(JsonNode row, Map<String, Station> stations, ImportCounts counts) {

        String trainNumber = bounded(requiredText(row, "trainNumber"), 30, "train number");

        String trainName = bounded(requiredText(row, "trainName"), 120, "train name");

        JsonNode routeData = row.path("completeOrderedRoute");

        if (!routeData.isArray() || routeData.size() < 2) {

            throw new BadRequestException("Train " + trainNumber + " must contain at least two route stops");

        }

        List<TrainStop> importedStops = new ArrayList<>(routeData.size());

        List<Station> routeStations = new ArrayList<>(routeData.size());

        int firstJourneyDay = requiredInt(routeData.get(0), "journeyDay", trainNumber);

        for (int index = 0; index < routeData.size(); index++) {

            JsonNode stopData = routeData.get(index);

            int sequence = requiredInt(stopData, "sequence", trainNumber);

            if (sequence != index + 1) {

                throw new BadRequestException("Train " + trainNumber

                        + " route sequence must be continuous starting at 1");

            }

            String stationCode = normalizeCode(requiredText(stopData, "stationCode"));

            Station station = stations.get(stationCode);

            if (station == null) {

                throw new BadRequestException("Station code " + stationCode

                        + " is missing from stations.json for train " + trainNumber);

            }

            int journeyDay = requiredInt(stopData, "journeyDay", trainNumber);

            int dayOffset = journeyDay - firstJourneyDay;

            if (dayOffset < 0) {

                throw new BadRequestException("Train " + trainNumber

                        + " contains a route stop before its first journey day");

            }

            TrainStop stop = new TrainStop();

            stop.setStopSequence(sequence);

            stop.setStation(station);

            stop.setArrivalTime(parseTime(stopData.get("arrivalTime"), trainNumber));

            stop.setDepartureTime(parseTime(stopData.get("departureTime"), trainNumber));

            stop.setDayOffset(dayOffset);

            importedStops.add(stop);

            routeStations.add(station);

        }

        validateEndpoints(row, trainNumber, routeStations);

        Train train = trainRepository.findByTrainNumber(trainNumber).orElse(null);

        if (train == null) {

            train = new Train();

            train.setTrainNumber(trainNumber);

            train.setTrainName(trainName);

            train.setStatus("ACTIVE");

            train = trainRepository.save(train);

            counts.trainsInserted++;

        } else if (!Objects.equals(train.getTrainName(), trainName)) {

            train.setTrainName(trainName);

            train = trainRepository.save(train);

            counts.trainsUpdated++;

        }

        TrainRoute existingRoute = trainRouteRepository

                .findByTrain_TrainIdOrderByRouteName(train.getTrainId()).stream()

                .filter(candidate -> ROUTE_NAME.equals(candidate.getRouteName()))

                .findFirst()

                .orElse(null);

        if (existingRoute != null) {

            List<TrainStop> existingStops = trainStopRepository

                    .findByTrainRoute_TrainRouteIdOrderByStopSequence(existingRoute.getTrainRouteId());

            if (!sameRoute(existingStops, importedStops)) {

                throw new BadRequestException("Existing GitHub dataset route differs for train " + trainNumber);

            }

            counts.routesReused++;

        } else {

            TrainRoute route = new TrainRoute();

            route.setTrain(train);

            route.setRouteName(ROUTE_NAME);

            TrainRoute savedRoute = trainRouteRepository.save(route);

            for (TrainStop stop : importedStops) {

                stop.setTrainRoute(savedRoute);

            }

            counts.stopsInserted += trainStopRepository.saveAll(importedStops).size();

            counts.routesInserted++;

        }

        counts.trainsImported++;

    }

    private void validateEndpoints(JsonNode train, String trainNumber, List<Station> routeStations) {

        String sourceCode = normalizeCode(requiredText(train.path("source"), "code"));

        String destinationCode = normalizeCode(requiredText(train.path("destination"), "code"));

        if (!sourceCode.equals(normalizeCode(routeStations.getFirst().getStationCode()))

                || !destinationCode.equals(normalizeCode(routeStations.getLast().getStationCode()))) {

            throw new BadRequestException("Train " + trainNumber

                    + " source/destination do not match its first and last route stops");

        }

    }

    private boolean sameRoute(List<TrainStop> existingStops, List<TrainStop> importedStops) {

        if (existingStops.size() != importedStops.size()) {

            return false;

        }

        for (int index = 0; index < existingStops.size(); index++) {

            TrainStop existing = existingStops.get(index);

            TrainStop imported = importedStops.get(index);

            if (!Objects.equals(existing.getStopSequence(), imported.getStopSequence())

                    || !Objects.equals(existing.getStation().getStationId(),

                    imported.getStation().getStationId())

                    || !Objects.equals(existing.getArrivalTime(), imported.getArrivalTime())

                    || !Objects.equals(existing.getDepartureTime(), imported.getDepartureTime())

                    || !Objects.equals(existing.getDayOffset(), imported.getDayOffset())) {

                return false;

            }

        }

        return true;

    }

    private void readArray(
            String file,
            int maxRecords,
            Consumer<JsonNode> recordConsumer) {

        readArray(file, 1, maxRecords, recordConsumer);
    }

    private void readArray(
            String file,
            int startFrom,
            int endAt,
            Consumer<JsonNode> recordConsumer) {

        try {
            restClient.get()
                    .uri(file)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {

                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new BadRequestException(
                                    "GitHub dataset request failed for " + file
                                            + " with HTTP " + response.getStatusCode().value());
                        }

                        try (JsonParser parser =
                                     objectMapper.tokenStreamFactory().createParser(response.getBody())) {

                            if (parser.nextToken() != JsonToken.START_ARRAY) {
                                throw new BadRequestException(file + " must contain a JSON array");
                            }

                            int recordsRead = 0;
                            JsonToken token;

                            while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
                                if (token == null) {
                                    throw new BadRequestException(
                                            file + " ended before its JSON array was complete");
                                }

                                JsonNode row = objectMapper.readerFor(JsonNode.class)
                                        .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                        .readValue(parser);

                                if (row == null || !row.isObject()) {
                                    throw new BadRequestException(
                                            file + " contains a non-object record");
                                }

                                recordsRead++;

                                // Already imported records ko sirf read karke skip karo.
                                if (recordsRead < startFrom) {
                                    continue;
                                }

                                try {
                                    recordConsumer.accept(row);
                                } catch (BadRequestException exception) {
                                    logger.warn(
                                            "Skipping invalid GitHub dataset record: file={}, record={}, reason={}",
                                            file, recordsRead, exception.getMessage());
                                }

                                // maxTrains = dataset ka last record number.
                                if (recordsRead >= endAt) {
                                    break;
                                }
                            }
                        } catch (JacksonException exception) {
                            throw new BadRequestException(
                                    "Unable to parse GitHub dataset file " + file
                                            + ": " + exception.getMessage());
                        }

                        return null;
                    });
        } catch (RestClientException exception) {
            throw new BadRequestException("Unable to download GitHub dataset file " + file);
        }
    }

    private String requiredText(JsonNode node, String field) {

        JsonNode value = node == null ? null : node.get(field);

        if (value == null || !value.isTextual() || value.asText().isBlank()) {

            throw new BadRequestException("GitHub dataset record is missing " + field);

        }

        return value.asText().trim();

    }

    private String optionalText(JsonNode node, String field) {

        JsonNode value = node == null ? null : node.get(field);

        return value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()

                ? null : value.asText().trim();

    }

    private int requiredInt(JsonNode node, String field, String trainNumber) {

        JsonNode value = node == null ? null : node.get(field);

        if (value == null || !value.canConvertToInt()) {

            throw new BadRequestException("Train " + trainNumber + " is missing valid " + field);

        }

        return value.intValue();

    }

    private LocalTime parseTime(JsonNode value, String trainNumber) {

        if (value == null || value.isNull()) {

            return null;

        }

        if (!value.isTextual()) {

            throw new BadRequestException("Train " + trainNumber + " has an invalid route time");

        }

        try {

            return LocalTime.parse(value.asText());

        } catch (DateTimeParseException exception) {

            throw new BadRequestException("Train " + trainNumber + " has an invalid route time");

        }

    }

    private String bounded(String value, int maxLength, String field) {

        if (value.length() > maxLength) {

            throw new BadRequestException("GitHub " + field + " exceeds the database column length");

        }

        return value;

    }

    private String boundedOptional(String value, int maxLength, String field) {

        return value == null ? null : bounded(value, maxLength, field);

    }

    private String normalizeCode(String value) {

        return value.trim().toUpperCase(Locale.ROOT);

    }

    public record ImportResult(

            int stationsProcessed,

            int stationsInserted,

            int stationsUpdated,

            int stationsUnchanged,

            int trainsInserted,

            int trainsUpdated,

            int routesInserted,

            int routesReused,

            int stopsInserted,

            int trainsImported

    ) {

    }

    private static class ImportCounts {

        private int stationsProcessed;

        private int stationsInserted;

        private int stationsUpdated;

        private int stationsUnchanged;

        private int trainsInserted;

        private int trainsUpdated;

        private int routesInserted;

        private int routesReused;

        private int stopsInserted;

        private int trainsImported;

    }

}
