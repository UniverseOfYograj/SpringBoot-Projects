package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.config.RedisCacheConfig;
import in.yograj.learnspringboot.railway_booking.dto.JourneyRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.JourneyResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.JourneyStopResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.SearchJourneyRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainSearchResponseDTO;
import in.yograj.learnspringboot.railway_booking.entity.*;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class JourneyServiceImpl implements JourneyService {

    private final JourneyRepository journeyRepository;
    private final TrainRepository trainRepository;
    private final StationRepository stationRepository;
    private final CoachRepository coachRepository;
    private final SeatRepository seatRepository;
    private final JourneySeatInventoryRepository inventoryRepository;
    private final TrainRouteRepository trainRouteRepository;
    private final TrainStopRepository trainStopRepository;
    private final JourneyStopRepository journeyStopRepository;
    private final OperationalJourneyCreator operationalJourneyCreator;

    @Autowired
    public JourneyServiceImpl(JourneyRepository journeyRepository,
                              TrainRepository trainRepository,
                              StationRepository stationRepository,
                              CoachRepository coachRepository,
                              SeatRepository seatRepository,
                              JourneySeatInventoryRepository inventoryRepository,
                              TrainRouteRepository trainRouteRepository,
                              TrainStopRepository trainStopRepository,
                              JourneyStopRepository journeyStopRepository,
                              OperationalJourneyCreator operationalJourneyCreator) {
        this.journeyRepository = journeyRepository;
        this.trainRepository = trainRepository;
        this.stationRepository = stationRepository;
        this.coachRepository = coachRepository;
        this.seatRepository = seatRepository;
        this.inventoryRepository = inventoryRepository;
        this.trainRouteRepository = trainRouteRepository;
        this.trainStopRepository = trainStopRepository;
        this.journeyStopRepository = journeyStopRepository;
        this.operationalJourneyCreator = operationalJourneyCreator;
    }

    public JourneyServiceImpl(JourneyRepository journeyRepository,
                              TrainRepository trainRepository,
                              StationRepository stationRepository,
                              CoachRepository coachRepository,
                              SeatRepository seatRepository,
                              JourneySeatInventoryRepository inventoryRepository,
                              TrainRouteRepository trainRouteRepository,
                              TrainStopRepository trainStopRepository,
                              JourneyStopRepository journeyStopRepository) {
        this(journeyRepository, trainRepository, stationRepository,
             coachRepository, seatRepository, inventoryRepository,
             trainRouteRepository, trainStopRepository, journeyStopRepository,
             new OperationalJourneyCreator(journeyRepository, journeyStopRepository, coachRepository, seatRepository, inventoryRepository));
    }

    @Override
    @Transactional
    @CacheEvict(value = RedisCacheConfig.JOURNEY_SEARCH_CACHE, allEntries = true)
    public JourneyResponseDTO createJourney(JourneyRequestDTO request) {
        if (request == null || request.trainId() == null || request.journeyDate() == null) {
            throw new BadRequestException("Train ID and journey service date are required");
        }

        Train train = trainRepository.findById(request.trainId())
                .orElseThrow(() -> new ResourceNotFoundException("Train not found with id: " + request.trainId()));

        Journey journey = new Journey();
        journey.setTrain(train);
        journey.setJourneyDate(request.journeyDate());
        List<JourneyStop> datedStops;

        if (request.trainRouteId() != null) {
            TrainRoute route = trainRouteRepository.findById(request.trainRouteId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Train route not found with id: " + request.trainRouteId()));
            if (!route.getTrain().getTrainId().equals(train.getTrainId())) {
                throw new BadRequestException("Selected route does not belong to the selected train");
            }
            List<TrainStop> routeStops = trainStopRepository
                    .findByTrainRoute_TrainRouteIdOrderByStopSequence(route.getTrainRouteId());
            if (routeStops.size() < 2) {
                throw new BadRequestException("Train route must have at least two stops");
            }

            journey.setTrainRoute(route);
            TrainStop first = routeStops.get(0);
            TrainStop last = routeStops.get(routeStops.size() - 1);
            if (first.getDepartureTime() == null || last.getArrivalTime() == null) {
                throw new BadRequestException("Route endpoints require departure and arrival times");
            }
            journey.setFromStation(first.getStation());
            journey.setToStation(last.getStation());
            journey.setDepartureTime(first.getDepartureTime());
            journey.setArrivalTime(last.getArrivalTime());
            datedStops = buildJourneyStops(journey, routeStops);
        } else {
            validateDirectJourney(request);
            Station from = stationRepository.findById(request.fromStationId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Source station not found with id: " + request.fromStationId()));
            Station to = stationRepository.findById(request.toStationId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Destination station not found with id: " + request.toStationId()));
            journey.setFromStation(from);
            journey.setToStation(to);
            journey.setDepartureTime(request.departureTime());
            journey.setArrivalTime(request.arrivalTime());
            datedStops = buildLegacyJourneyStops(journey, from, to, request.departureTime(), request.arrivalTime());
        }

        if (journeyRepository.existsByTrain_TrainIdAndFromStation_StationIdAndToStation_StationIdAndJourneyDate(
                train.getTrainId(), journey.getFromStation().getStationId(),
                journey.getToStation().getStationId(), journey.getJourneyDate())) {
            throw new BadRequestException("Journey already exists for this train, route and date");
        }

        Journey saved = journeyRepository.save(journey);
        for (JourneyStop stop : datedStops) {
            stop.setJourney(saved);
        }
        journeyStopRepository.saveAll(datedStops);
        operationalJourneyCreator.seedMultiClassInventory(saved);
        return mapToResponse(saved, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public JourneyResponseDTO getJourneyById(Long journeyId) {
        Journey journey = journeyRepository.findById(journeyId)
                .orElseThrow(() -> new ResourceNotFoundException("Journey not found with id: " + journeyId));
        return mapToResponse(journey, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JourneyStopResponseDTO> getJourneyStops(Long journeyId) {
        if (!journeyRepository.existsById(journeyId)) {
            throw new ResourceNotFoundException("Journey not found with id: " + journeyId);
        }
        return journeyStopRepository.findByJourney_JourneyIdOrderByStopSequence(journeyId).stream()
                .map(stop -> new JourneyStopResponseDTO(
                        stop.getJourneyStopId(),
                        stop.getStation().getStationId(),
                        stop.getStation().getStationCode(),
                        stop.getStation().getStationName(),
                        stop.getStopSequence(),
                        stop.getArrivalAt(),
                        stop.getDepartureAt()
                )).toList();
    }

    @Override
    @Transactional
    public List<JourneyResponseDTO> searchJourneys(SearchJourneyRequestDTO request) {
        if (request == null) {
            throw new BadRequestException("Journey search request is required");
        }
        return searchJourneys(request.fromStationId(), request.toStationId(), request.journeyDate());
    }

    @Override
    @Transactional
    public List<JourneyResponseDTO> searchJourneys(Long fromStationId, Long toStationId, LocalDate journeyDate) {
        if (fromStationId == null || toStationId == null || journeyDate == null) {
            throw new BadRequestException("fromStationId, toStationId and journeyDate are required for search");
        }
        if (fromStationId.equals(toStationId)) {
            throw new BadRequestException("Source and destination station cannot be the same");
        }
        if (!stationRepository.existsById(fromStationId)) {
            throw new ResourceNotFoundException("Source station not found with id: " + fromStationId);
        }
        if (!stationRepository.existsById(toStationId)) {
            throw new ResourceNotFoundException("Destination station not found with id: " + toStationId);
        }

        List<JourneyResponseDTO> results = new ArrayList<>();
        for (Journey journey : journeyRepository.findByJourneyDate(journeyDate)) {
            List<JourneyStop> stops = journeyStopRepository
                    .findByJourney_JourneyIdOrderByStopSequence(journey.getJourneyId());
            Optional<JourneyStop> origin = stops.stream()
                    .filter(stop -> stop.getStation().getStationId().equals(fromStationId))
                    .findFirst();
            Optional<JourneyStop> destination = stops.stream()
                    .filter(stop -> stop.getStation().getStationId().equals(toStationId))
                    .filter(stop -> origin.isPresent()
                            && stop.getStopSequence() > origin.get().getStopSequence())
                    .findFirst();

            if (origin.isPresent() && destination.isPresent()) {
                results.add(mapToResponse(journey, origin.get(), destination.get()));
            } else if (stops.isEmpty()
                    && journey.getFromStation().getStationId().equals(fromStationId)
                    && journey.getToStation().getStationId().equals(toStationId)) {
                results.add(mapToResponse(journey, null, null));
            }
        }
        if (!results.isEmpty()) {
            return results;
        }

        // Lazy generation for connecting trains if not yet created for this journeyDate
        Station fromStation = stationRepository.findById(fromStationId).orElse(null);
        Station toStation = stationRepository.findById(toStationId).orElse(null);
        if (fromStation != null && toStation != null) {
            List<TrainSearchResponseDTO> connectingTrains = trainStopRepository
                    .findTrainStopsBetweenStations(fromStation.getStationCode(), toStation.getStationCode());
            for (TrainSearchResponseDTO trainDto : connectingTrains) {
            try {
                Journey journey = getOrCreateOperationalJourney(trainDto.trainId(), journeyDate);
                List<JourneyStop> stops = journeyStopRepository
                        .findByJourney_JourneyIdOrderByStopSequence(journey.getJourneyId());
                Optional<JourneyStop> origin = stops.stream()
                        .filter(stop -> stop.getStation().getStationId().equals(fromStationId))
                        .findFirst();
                Optional<JourneyStop> destination = stops.stream()
                        .filter(stop -> stop.getStation().getStationId().equals(toStationId))
                        .filter(stop -> origin.isPresent()
                                && stop.getStopSequence() > origin.get().getStopSequence())
                        .findFirst();
                if (origin.isPresent() && destination.isPresent()) {
                    results.add(mapToResponse(journey, origin.get(), destination.get()));
                }
            } catch (Exception ignored) {
                // Ignore non-running or invalid date trains
            }
            }
        }
        return results;
    }

    @Override
    @Transactional
    public List<JourneyResponseDTO> getAllJourneys() {
        return getAllJourneys(null, null);
    }

    @Override
    @Transactional
    public List<JourneyResponseDTO> getAllJourneys(Long trainId, LocalDate date) {
        if (trainId != null && date != null) {
            Journey journey = getOrCreateOperationalJourney(trainId, date);
            return List.of(mapToResponse(journey, null, null));
        }

        List<Journey> journeys;
        if (date != null) {
            journeys = journeyRepository.findByJourneyDate(date);
        } else {
            journeys = journeyRepository.findAll();
        }
        return journeys.stream()
                .filter(j -> trainId == null || (j.getTrain() != null && j.getTrain().getTrainId().equals(trainId)))
                .map(journey -> mapToResponse(journey, null, null))
                .toList();
    }

    @Override
    public Journey getOrCreateOperationalJourney(Long trainId, LocalDate date) {
        if (trainId == null || date == null) {
            throw new BadRequestException("Train ID and travel date are required");
        }

        // 1. Fast existence check
        Optional<Journey> existing = journeyRepository.findByTrain_TrainIdAndJourneyDate(trainId, date);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 2. Validate train
        Train train = trainRepository.findById(trainId)
                .orElseThrow(() -> new ResourceNotFoundException("Train not found with id: " + trainId));

        // 3. Validate operating days
        if (!train.operatesOn(date.getDayOfWeek())) {
            throw new BadRequestException("Train " + train.getTrainNumber() + " (" + train.getTrainName()
                    + ") is not scheduled to operate on " + date.getDayOfWeek() + " (" + date + ")");
        }

        // 4. Validate booking horizon (up to 120 days in advance)
        LocalDate today = LocalDate.now();
        if (date.isBefore(today.minusDays(1))) {
            throw new BadRequestException("Cannot book journey in the past: " + date);
        }
        if (date.isAfter(today.plusDays(120))) {
            throw new BadRequestException("Journey date " + date + " is beyond the 120-day booking horizon");
        }

        // 5. Retrieve primary route and route stops
        List<TrainRoute> routes = trainRouteRepository.findByTrain_TrainIdOrderByRouteName(train.getTrainId());
        if (routes.isEmpty()) {
            throw new BadRequestException("No route found for train: " + train.getTrainNumber());
        }
        TrainRoute route = routes.get(0);
        List<TrainStop> routeStops = trainStopRepository.findByTrainRoute_TrainRouteIdOrderByStopSequence(route.getTrainRouteId());
        if (routeStops.size() < 2) {
            throw new BadRequestException("Train route requires at least 2 stops for train: " + train.getTrainNumber());
        }

        // 6. Concurrency-safe creation attempt with REQUIRES_NEW transaction rollback isolation
        for (int attempt = 1; attempt <= 3; attempt++) {
            existing = journeyRepository.findByTrain_TrainIdAndJourneyDate(trainId, date);
            if (existing.isPresent()) {
                return existing.get();
            }
            try {
                return operationalJourneyCreator.createOperationalJourneyInNewTransaction(train, route, routeStops, date);
            } catch (DataIntegrityViolationException ex) {
                // The failed REQUIRES_NEW transaction was rolled back cleanly.
                // Winning transaction is either committed or completing. Poll for it.
                for (int poll = 0; poll < 10; poll++) {
                    existing = journeyRepository.findByTrain_TrainIdAndJourneyDate(trainId, date);
                    if (existing.isPresent()) {
                        return existing.get();
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while waiting for concurrent journey creation", ie);
                    }
                }
            }
        }

        return journeyRepository.findByTrain_TrainIdAndJourneyDate(trainId, date)
                .orElseThrow(() -> new IllegalStateException("Could not create or locate operational journey for train " + trainId + " on " + date));
    }

    private List<JourneyStop> buildJourneyStops(Journey journey, List<TrainStop> routeStops) {
        return routeStops.stream().map(trainStop -> {
            JourneyStop stop = new JourneyStop();
            stop.setJourney(journey);
            stop.setTrainStop(trainStop);
            stop.setStation(trainStop.getStation());
            stop.setStopSequence(trainStop.getStopSequence());
            LocalDate stopDate = journey.getJourneyDate().plusDays(trainStop.getDayOffset());
            stop.setArrivalAt(trainStop.getArrivalTime() == null ? null
                    : LocalDateTime.of(stopDate, trainStop.getArrivalTime()));
            stop.setDepartureAt(trainStop.getDepartureTime() == null ? null
                    : LocalDateTime.of(stopDate, trainStop.getDepartureTime()));
            stop.setDistanceFromOriginKm(trainStop.getDistanceFromOriginKm());
            return stop;
        }).toList();
    }

    private List<JourneyStop> buildLegacyJourneyStops(Journey journey, Station from, Station to,
                                                       LocalTime departure, LocalTime arrival) {
        LocalDateTime departureAt = LocalDateTime.of(journey.getJourneyDate(), departure);
        LocalDate arrivalDate = arrival.isBefore(departure)
                ? journey.getJourneyDate().plusDays(1) : journey.getJourneyDate();
        LocalDateTime arrivalAt = LocalDateTime.of(arrivalDate, arrival);
        if (!arrivalAt.isAfter(departureAt)) {
            throw new BadRequestException("Journey arrival must be after departure");
        }

        JourneyStop origin = new JourneyStop();
        origin.setJourney(journey);
        origin.setStation(from);
        origin.setStopSequence(1);
        origin.setDepartureAt(departureAt);

        JourneyStop destination = new JourneyStop();
        destination.setJourney(journey);
        destination.setStation(to);
        destination.setStopSequence(2);
        destination.setArrivalAt(arrivalAt);
        return List.of(origin, destination);
    }

    private void validateDirectJourney(JourneyRequestDTO request) {
        if (request.fromStationId() == null || request.toStationId() == null
                || request.departureTime() == null || request.arrivalTime() == null) {
            throw new BadRequestException(
                    "For a legacy direct journey, source, destination, departure and arrival are required");
        }
        if (request.fromStationId().equals(request.toStationId())) {
            throw new BadRequestException("Source and destination station cannot be the same");
        }
    }

    private JourneyResponseDTO mapToResponse(Journey journey, JourneyStop segmentOrigin, JourneyStop segmentDestination) {
        List<JourneyStop> stops = journeyStopRepository
                .findByJourney_JourneyIdOrderByStopSequence(journey.getJourneyId());
        JourneyStop origin = segmentOrigin != null ? segmentOrigin : (stops.isEmpty() ? null : stops.get(0));
        JourneyStop destination = segmentDestination != null ? segmentDestination
                : (stops.isEmpty() ? null : stops.get(stops.size() - 1));

        LocalTime departure = origin != null && origin.getDepartureAt() != null
                ? origin.getDepartureAt().toLocalTime() : journey.getDepartureTime();
        LocalTime arrival = destination != null && destination.getArrivalAt() != null
                ? destination.getArrivalAt().toLocalTime() : journey.getArrivalTime();
        return new JourneyResponseDTO(
                journey.getJourneyId(),
                journey.getTrain().getTrainId(),
                journey.getTrain().getTrainNumber(),
                journey.getTrain().getTrainName(),
                origin == null ? journey.getFromStation().getStationId() : origin.getStation().getStationId(),
                origin == null ? journey.getFromStation().getStationCode() : origin.getStation().getStationCode(),
                origin == null ? journey.getFromStation().getStationName() : origin.getStation().getStationName(),
                destination == null ? journey.getToStation().getStationId() : destination.getStation().getStationId(),
                destination == null ? journey.getToStation().getStationCode() : destination.getStation().getStationCode(),
                destination == null ? journey.getToStation().getStationName() : destination.getStation().getStationName(),
                journey.getJourneyDate(),
                departure,
                arrival,
                journey.getTrainRoute() == null ? null : journey.getTrainRoute().getTrainRouteId(),
                origin == null ? null : origin.getDepartureAt(),
                destination == null ? null : destination.getArrivalAt()
        );
    }
}
