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
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubRailwayDataImportServiceTests {

    private static final String DATASET_BASE_URL =
            "https://raw.githubusercontent.com/prasenjit-27/Indian-Railway-Data/"
                    + "1dc0ddbc6a06b09ef69d4825ff3127c1f29640fa/";


    @Test
    void importsSampleAndRepeatsWithoutDuplicatingStationsTrainsRoutesOrStops() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        for (int run = 0; run < 2; run++) {
            server.expect(requestTo(DATASET_BASE_URL + "stations.json"))
                    .andRespond(withSuccess(stationsJson(), MediaType.APPLICATION_JSON));
            server.expect(requestTo(DATASET_BASE_URL + "trains.json"))
                    .andRespond(withSuccess(trainsJson(), MediaType.APPLICATION_JSON));
        }

        Station existingStation = station(1L, "NDLS", "NEW DELHI", "New Delhi", "Delhi");
        Map<String, Station> stations = new HashMap<>();
        stations.put(existingStation.getStationCode(), existingStation);
        StationRepository stationRepository = mock(StationRepository.class);
        when(stationRepository.findAll()).thenAnswer(invocation -> new ArrayList<>(stations.values()));
        when(stationRepository.findByStationCode(any())).thenAnswer(invocation ->
                Optional.ofNullable(stations.get(invocation.getArgument(0))));
        when(stationRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<Station> saved = invocation.getArgument(0);
            for (Station station : saved) {
                if (station.getStationId() == null) {
                    station.setStationId(2L);
                }
                stations.put(station.getStationCode(), station);
            }
            return saved;
        });

        Map<String, Train> trains = new HashMap<>();
        TrainRepository trainRepository = mock(TrainRepository.class);
        when(trainRepository.findByTrainNumber("12951")).thenAnswer(invocation ->
                Optional.ofNullable(trains.get("12951")));
        when(trainRepository.save(any(Train.class))).thenAnswer(invocation -> {
            Train train = invocation.getArgument(0);
            train.setTrainId(10L);
            trains.put(train.getTrainNumber(), train);
            return train;
        });

        Map<Long, List<TrainRoute>> routesByTrain = new HashMap<>();
        TrainRouteRepository routeRepository = mock(TrainRouteRepository.class);
        when(routeRepository.findByTrain_TrainIdOrderByRouteName(10L)).thenAnswer(invocation ->
                routesByTrain.getOrDefault(10L, List.of()));
        when(routeRepository.save(any(TrainRoute.class))).thenAnswer(invocation -> {
            TrainRoute route = invocation.getArgument(0);
            route.setTrainRouteId(20L);
            routesByTrain.computeIfAbsent(10L, ignored -> new ArrayList<>()).add(route);
            return route;
        });

        Map<Long, List<TrainStop>> stopsByRoute = new HashMap<>();
        TrainStopRepository stopRepository = mock(TrainStopRepository.class);
        when(stopRepository.findByTrainRoute_TrainRouteIdOrderByStopSequence(20L)).thenAnswer(invocation ->
                stopsByRoute.getOrDefault(20L, List.of()));
        when(stopRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<TrainStop> saved = invocation.getArgument(0);
            stopsByRoute.put(20L, saved);
            return saved;
        });

        GitHubRailwayDataImportService service = new GitHubRailwayDataImportService(
                restClientBuilder, new ObjectMapper(), stationRepository, trainRepository,
                routeRepository, stopRepository);

        var first = service.importData(1, 1);
        var second = service.importData(1, 1);

        assertEquals(2, first.stationsProcessed());
        assertEquals(1, first.stationsInserted());
        assertEquals(1, first.stationsUnchanged());
        assertEquals(1, first.trainsInserted());
        assertEquals(1, first.routesInserted());
        assertEquals(2, first.stopsInserted());
        assertEquals(2, second.stationsUnchanged());
        assertEquals(0, second.trainsInserted());
        assertEquals(1, second.routesReused());
        assertEquals(0, second.stopsInserted());

        Station importedStation = stations.get("BCT");
        assertEquals("MUMBAI CENTRAL", importedStation.getStationName());
        assertEquals("Maharashtra", importedStation.getState());
        assertNull(importedStation.getCity());
        assertEquals("New Delhi", existingStation.getCity());

        List<TrainStop> importedStops = stopsByRoute.get(20L);
        assertEquals("NDLS", importedStops.getFirst().getStation().getStationCode());
        assertEquals("BCT", importedStops.getLast().getStation().getStationCode());
        assertNull(importedStops.getFirst().getArrivalTime());
        assertEquals(LocalTime.of(17, 0), importedStops.getFirst().getDepartureTime());
        assertEquals(0, importedStops.getFirst().getDayOffset());
        assertEquals(1, importedStops.getLast().getDayOffset());
        assertEquals(LocalTime.of(8, 32), importedStops.getLast().getArrivalTime());
        verify(stationRepository, times(1)).saveAll(anyList());
        verify(routeRepository, times(1)).save(any(TrainRoute.class));
        server.verify();
    }

    private Station station(long id, String code, String name, String city, String state) {
        Station station = new Station();
        station.setStationId(id);
        station.setStationCode(code);
        station.setStationName(name);
        station.setCity(city);
        station.setState(state);
        return station;
    }

    private String stationsJson() {
        return """
                [
                  {"code":"NDLS","name":"NEW DELHI","state":"Delhi","zone":"NR","address":"Delhi",
                   "coordinates":{"latitude":28.6,"longitude":77.2}},
                  {"code":"BCT","name":"MUMBAI CENTRAL","state":"Maharashtra","zone":"WR","address":"Mumbai",
                   "coordinates":{"latitude":18.9,"longitude":72.8}}
                ]
                """;
    }

    private String trainsJson() {
        return """
                [
                  {
                    "trainNumber":"12951",
                    "trainName":"MUMBAI TEJAS RAJDHANI",
                    "type":"Raj",
                    "source":{"code":"NDLS","name":"NEW DELHI"},
                    "destination":{"code":"BCT","name":"MUMBAI CENTRAL"},
                    "runningDays":{"SUN":true,"MON":true},
                    "overallDistanceKm":1386,
                    "completeOrderedRoute":[
                      {"sequence":1,"stationCode":"NDLS","stationName":"NEW DELHI",
                       "arrivalTime":null,"departureTime":"17:00:00","journeyDay":1,"distance":0},
                      {"sequence":2,"stationCode":"BCT","stationName":"MUMBAI CENTRAL",
                       "arrivalTime":"08:32:00","departureTime":null,"journeyDay":2,"distance":1386}
                    ]
                  }
                ]
                """;
    }
}
