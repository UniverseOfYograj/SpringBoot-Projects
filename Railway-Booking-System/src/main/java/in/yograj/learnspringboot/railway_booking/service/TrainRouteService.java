package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.dto.CreateTrainRouteRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainRouteResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainStopRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainStopResponseDTO;
import in.yograj.learnspringboot.railway_booking.entity.Station;
import in.yograj.learnspringboot.railway_booking.entity.Train;
import in.yograj.learnspringboot.railway_booking.entity.TrainRoute;
import in.yograj.learnspringboot.railway_booking.entity.TrainStop;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.repository.StationRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainRouteRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainStopRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
public class TrainRouteService {

    private final TrainRepository trainRepository;
    private final StationRepository stationRepository;
    private final TrainRouteRepository routeRepository;
    private final TrainStopRepository stopRepository;

    public TrainRouteService(TrainRepository trainRepository,
                             StationRepository stationRepository,
                             TrainRouteRepository routeRepository,
                             TrainStopRepository stopRepository) {
        this.trainRepository = trainRepository;
        this.stationRepository = stationRepository;
        this.routeRepository = routeRepository;
        this.stopRepository = stopRepository;
    }

    @Transactional
    public TrainRouteResponseDTO createRoute(Long trainId, CreateTrainRouteRequestDTO request) {
        Train train = trainRepository.findById(trainId)
                .orElseThrow(() -> new ResourceNotFoundException("Train not found with id: " + trainId));

        String routeName = request.routeName().trim();
        if (routeRepository.existsByTrain_TrainIdAndRouteName(trainId, routeName)) {
            throw new BadRequestException("Route name already exists for this train");
        }

        List<TrainStopRequestDTO> stops = request.stops().stream()
                .sorted(Comparator.comparing(TrainStopRequestDTO::stopSequence))
                .toList();
        validateStops(stops);

        TrainRoute route = new TrainRoute();
        route.setTrain(train);
        route.setRouteName(routeName);
        TrainRoute savedRoute = routeRepository.save(route);

        List<TrainStop> savedStops = stops.stream().map(stopRequest -> {
            Station station = stationRepository.findById(stopRequest.stationId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Station not found with id: " + stopRequest.stationId()));

            TrainStop stop = new TrainStop();
            stop.setTrainRoute(savedRoute);
            stop.setStation(station);
            stop.setStopSequence(stopRequest.stopSequence());
            stop.setArrivalTime(stopRequest.arrivalTime());
            stop.setDepartureTime(stopRequest.departureTime());
            stop.setDayOffset(stopRequest.dayOffset());
            return stopRepository.save(stop);
        }).toList();

        return mapRoute(savedRoute, savedStops);
    }

    @Transactional(readOnly = true)
    public List<TrainRouteResponseDTO> getRoutes(Long trainId) {
        if (!trainRepository.existsById(trainId)) {
            throw new ResourceNotFoundException("Train not found with id: " + trainId);
        }
        return routeRepository.findByTrain_TrainIdOrderByRouteName(trainId).stream()
                .map(route -> mapRoute(route,
                        stopRepository.findByTrainRoute_TrainRouteIdOrderByStopSequence(route.getTrainRouteId())))
                .toList();
    }

    private void validateStops(List<TrainStopRequestDTO> stops) {
        if (stops.size() < 2) {
            throw new BadRequestException("A train route must have at least two stops");
        }

        LocalDate serviceDate = LocalDate.of(2000, 1, 1);
        LocalDateTime previousEvent = null;
        for (int index = 0; index < stops.size(); index++) {
            TrainStopRequestDTO stop = stops.get(index);
            if (stop.stopSequence() != index + 1) {
                throw new BadRequestException("Stop sequence must be continuous starting at 1");
            }
            if (index == 0 && stop.dayOffset() != 0) {
                throw new BadRequestException("First route stop must use day offset 0");
            }
            if (stop.dayOffset() < 0) {
                throw new BadRequestException("Stop day offset cannot be negative");
            }
            if (index == 0 && stop.departureTime() == null) {
                throw new BadRequestException("First route stop must have a departure time");
            }
            if (index == stops.size() - 1 && stop.arrivalTime() == null) {
                throw new BadRequestException("Last route stop must have an arrival time");
            }
            if (index > 0 && index < stops.size() - 1
                    && (stop.arrivalTime() == null || stop.departureTime() == null)) {
                throw new BadRequestException("Intermediate stops require arrival and departure times");
            }

            LocalDateTime arrival = stop.arrivalTime() == null ? null
                    : LocalDateTime.of(serviceDate.plusDays(stop.dayOffset()), stop.arrivalTime());
            LocalDateTime departure = stop.departureTime() == null ? null
                    : LocalDateTime.of(serviceDate.plusDays(stop.dayOffset()), stop.departureTime());
            if (arrival != null && departure != null && departure.isBefore(arrival)) {
                throw new BadRequestException("Stop departure must not be before its arrival");
            }
            LocalDateTime firstEvent = arrival != null ? arrival : departure;
            if (previousEvent != null && firstEvent.isBefore(previousEvent)) {
                throw new BadRequestException("Train stop times must be chronological");
            }
            previousEvent = departure != null ? departure : arrival;
        }
    }

    private TrainRouteResponseDTO mapRoute(TrainRoute route, List<TrainStop> stops) {
        return new TrainRouteResponseDTO(
                route.getTrainRouteId(),
                route.getTrain().getTrainId(),
                route.getRouteName(),
                stops.stream().map(stop -> new TrainStopResponseDTO(
                        stop.getTrainStopId(),
                        stop.getStation().getStationId(),
                        stop.getStation().getStationCode(),
                        stop.getStation().getStationName(),
                        stop.getStopSequence(),
                        stop.getArrivalTime(),
                        stop.getDepartureTime(),
                        stop.getDayOffset()
                )).toList()
        );
    }
}
