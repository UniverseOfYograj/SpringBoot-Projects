package in.yograj.learnspringboot.railway_booking.integration.railradar;

import in.yograj.learnspringboot.railway_booking.entity.Station;
import in.yograj.learnspringboot.railway_booking.entity.Train;
import in.yograj.learnspringboot.railway_booking.entity.TrainRoute;
import in.yograj.learnspringboot.railway_booking.entity.TrainStop;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.StationSyncResponseDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.mapper.RailRadarStationMapping;
import in.yograj.learnspringboot.railway_booking.integration.railradar.mapper.RailRadarTrainImport;
import in.yograj.learnspringboot.railway_booking.repository.StationRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainRouteRepository;
import in.yograj.learnspringboot.railway_booking.repository.TrainStopRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RailRadarPersistenceServiceTests {

    @Test
    void repeatedStationSyncReusesExistingStateAndDoesNotInsertDuplicates() {
        StationRepository stationRepository = mock(StationRepository.class);
        Station stored = station(1L, "NDLS");
        stored.setStationName("Old New Delhi");
        stored.setCity("Old city");
        stored.setState("Delhi");
        when(stationRepository.findByStationCode("NDLS"))
                .thenReturn(Optional.of(stored));
        when(stationRepository.save(any(Station.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RailRadarPersistenceService service = new RailRadarPersistenceService(
                stationRepository,
                mock(TrainRepository.class),
                mock(TrainRouteRepository.class),
                mock(TrainStopRepository.class));

        RailRadarStationMapping mapping = mapping(List.of(
                station(null, "NDLS", "New Delhi", "New Delhi")));
        StationSyncResponseDTO first = service.persistStations(mapping);
        StationSyncResponseDTO second = service.persistStations(mapping);

        assertEquals(0, first.inserted());
        assertEquals(1, first.updated());
        assertEquals(0, second.inserted());
        assertEquals(0, second.updated());
        assertEquals(1, second.unchanged());
        assertEquals("Delhi", stored.getState());
        verify(stationRepository, times(1)).save(any(Station.class));
    }

    @Test
    void insertsNewStationWithoutState() {
        StationRepository stationRepository = mock(StationRepository.class);
        when(stationRepository.findByStationCode("SRM")).thenReturn(Optional.empty());
        RailRadarPersistenceService service = new RailRadarPersistenceService(
                stationRepository,
                mock(TrainRepository.class),
                mock(TrainRouteRepository.class),
                mock(TrainStopRepository.class));

        StationSyncResponseDTO response = service.persistStations(mapping(List.of(
                station(null, "SRM", "Sarmatanr", "Satna"))));

        assertEquals(1, response.fetched());
        assertEquals(1, response.inserted());
        assertEquals(0, response.skipped());
        assertTrue(response.unresolved().isEmpty());
        verify(stationRepository).save(org.mockito.ArgumentMatchers.argThat(
                saved -> saved.getState() == null
                        && "SRM".equals(saved.getStationCode())
                        && "Sarmatanr".equals(saved.getStationName())
                        && "Satna".equals(saved.getCity())));
    }

    @Test
    void skipsNewStationWhenRailRadarOmitsRequiredNameOrCity() {
        StationRepository stationRepository = mock(StationRepository.class);
        when(stationRepository.findByStationCode("STA")).thenReturn(Optional.empty());
        when(stationRepository.save(any(Station.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RailRadarPersistenceService service = new RailRadarPersistenceService(
                stationRepository,
                mock(TrainRepository.class),
                mock(TrainRouteRepository.class),
                mock(TrainStopRepository.class));

        StationSyncResponseDTO response = service.persistStations(mapping(List.of(
                station(null, "STA", null, "Satna"))));

        assertEquals(0, response.inserted());
        assertEquals(1, response.skipped());
        assertEquals("STA", response.unresolved().getFirst().stationCode());
        verify(stationRepository, never()).save(any(Station.class));
    }

    @Test
    void stationUpsertPreservesExistingLocalState() {
        StationRepository stationRepository = mock(StationRepository.class);
        Station existing = station(1L, "NDLS");
        existing.setState("Delhi");
        when(stationRepository.findByStationCode("NDLS")).thenReturn(Optional.of(existing));
        RailRadarPersistenceService service = new RailRadarPersistenceService(
                stationRepository,
                mock(TrainRepository.class),
                mock(TrainRouteRepository.class),
                mock(TrainStopRepository.class));

        StationSyncResponseDTO response = service.persistStations(mapping(List.of(
                station(null, "NDLS", "New Delhi", "New Delhi"))));

        assertEquals(1, response.updated());
        assertEquals("Delhi", existing.getState());
        verify(stationRepository).save(existing);
    }

    @Test
    void repeatedTrainSyncReusesAnIdenticalRouteAndItsStops() {
        StationRepository stationRepository = mock(StationRepository.class);
        TrainRepository trainRepository = mock(TrainRepository.class);
        TrainRouteRepository routeRepository = mock(TrainRouteRepository.class);
        TrainStopRepository stopRepository = mock(TrainStopRepository.class);
        Station origin = station(1L, "NDLS");
        Station destination = station(2L, "BCT");
        Train train = new Train();
        train.setTrainId(10L);
        train.setTrainNumber("12919");
        train.setTrainName("Malwa Express");
        TrainRoute route = new TrainRoute();
        route.setTrainRouteId(20L);
        route.setTrain(train);
        route.setRouteName("RailRadar timetable");
        TrainStop first = stop(route, origin, 1, null, LocalTime.of(20, 0), 0);
        TrainStop last = stop(route, destination, 2, LocalTime.of(8, 0), null, 1);

        when(trainRepository.findByTrainNumber("12919")).thenReturn(Optional.of(train));
        when(routeRepository.findByTrain_TrainIdOrderByRouteName(10L)).thenReturn(List.of(route));
        when(stopRepository.findByTrainRoute_TrainRouteIdOrderByStopSequence(20L))
                .thenReturn(List.of(first, last));
        when(stationRepository.findByStationCode("NDLS")).thenReturn(Optional.of(origin));
        when(stationRepository.findByStationCode("BCT")).thenReturn(Optional.of(destination));

        RailRadarPersistenceService service = new RailRadarPersistenceService(
                stationRepository, trainRepository, routeRepository, stopRepository);

        var result = service.persistTrain(new RailRadarTrainImport("12919", "Malwa Express", List.of(
                new RailRadarTrainImport.Stop(1, "NDLS", null, LocalTime.of(20, 0), 0),
                new RailRadarTrainImport.Stop(2, "BCT", LocalTime.of(8, 0), null, 1))), List.of());

        assertEquals(0, result.trainInserted());
        assertEquals(0, result.trainUpdated());
        assertEquals(0, result.stopsInserted());
        assertTrue(result.routeReused());
        verify(routeRepository, never()).save(any(TrainRoute.class));
        verify(stopRepository, never()).saveAll(any());
        verify(trainRepository, never()).save(any(Train.class));
    }

    @Test
    void persistsTrainRouteAndOrderedStopsFromMappedTimetable() {
        StationRepository stationRepository = mock(StationRepository.class);
        TrainRepository trainRepository = mock(TrainRepository.class);
        TrainRouteRepository routeRepository = mock(TrainRouteRepository.class);
        TrainStopRepository stopRepository = mock(TrainStopRepository.class);
        Station origin = station(1L, "NDLS");
        Station destination = station(2L, "BCT");

        when(trainRepository.findByTrainNumber("12919")).thenReturn(Optional.empty());
        when(trainRepository.save(any(Train.class))).thenAnswer(invocation -> {
            Train saved = invocation.getArgument(0);
            saved.setTrainId(10L);
            return saved;
        });
        when(stationRepository.findByStationCode("NDLS")).thenReturn(Optional.of(origin));
        when(stationRepository.findByStationCode("BCT")).thenReturn(Optional.of(destination));
        when(routeRepository.save(any(TrainRoute.class))).thenAnswer(invocation -> {
            TrainRoute saved = invocation.getArgument(0);
            saved.setTrainRouteId(20L);
            return saved;
        });
        doAnswer(invocation -> {
            Iterable<TrainStop> toSave = invocation.getArgument(0);
            List<TrainStop> saved = new ArrayList<>();
            toSave.forEach(saved::add);
            assertEquals(2, saved.size());
            assertEquals(1, saved.get(0).getStopSequence());
            assertEquals("NDLS", saved.get(0).getStation().getStationCode());
            assertEquals(2, saved.get(1).getStopSequence());
            assertEquals("BCT", saved.get(1).getStation().getStationCode());
            return saved;
        }).when(stopRepository).saveAll(any());

        RailRadarPersistenceService service = new RailRadarPersistenceService(
                stationRepository, trainRepository, routeRepository, stopRepository);

        var result = service.persistTrain(new RailRadarTrainImport("12919", "Malwa Express", List.of(
                new RailRadarTrainImport.Stop(1, "NDLS", null, LocalTime.of(20, 0), 0),
                new RailRadarTrainImport.Stop(2, "BCT", LocalTime.of(8, 0), null, 1))), List.of());

        assertEquals(1, result.trainInserted());
        assertEquals(2, result.stopsInserted());
        assertFalse(result.routeReused());
    }

    @Test
    void createsMissingStationOnceAndUsesItForRepeatedRouteStops() {
        StationRepository stationRepository = mock(StationRepository.class);
        TrainRepository trainRepository = mock(TrainRepository.class);
        TrainRouteRepository routeRepository = mock(TrainRouteRepository.class);
        TrainStopRepository stopRepository = mock(TrainStopRepository.class);
        Station newStation = station(null, "DADN", "Dr. Ambedkar Nagar", "Indore");

        when(trainRepository.findByTrainNumber("12919")).thenReturn(Optional.empty());
        when(trainRepository.save(any(Train.class))).thenAnswer(invocation -> {
            Train train = invocation.getArgument(0);
            train.setTrainId(10L);
            return train;
        });
        when(stationRepository.findByStationCode("DADN")).thenReturn(Optional.empty());
        when(stationRepository.save(any(Station.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(routeRepository.save(any(TrainRoute.class))).thenAnswer(invocation -> {
            TrainRoute route = invocation.getArgument(0);
            route.setTrainRouteId(20L);
            return route;
        });
        doAnswer(invocation -> {
            Iterable<TrainStop> stops = invocation.getArgument(0);
            List<TrainStop> saved = new ArrayList<>();
            stops.forEach(saved::add);
            assertEquals(2, saved.size());
            assertTrue(saved.get(0).getStation() == saved.get(1).getStation());
            assertNull(saved.get(0).getStation().getState());
            return saved;
        }).when(stopRepository).saveAll(any());

        RailRadarPersistenceService service = new RailRadarPersistenceService(
                stationRepository, trainRepository, routeRepository, stopRepository);
        var result = service.persistTrain(new RailRadarTrainImport("12919", "Malwa Express", List.of(
                new RailRadarTrainImport.Stop(1, "DADN", null, LocalTime.of(20, 0), 0),
                new RailRadarTrainImport.Stop(2, "DADN", LocalTime.of(21, 0), null, 0))),
                List.of(newStation));

        assertEquals(1, result.trainInserted());
        assertEquals(2, result.stopsInserted());
        verify(stationRepository, times(1)).save(newStation);
    }

    private Station station(long id, String code) {
        return station(id, code, code, code);
    }

    private Station station(Long id, String code, String name, String city) {
        Station station = new Station();
        station.setStationId(id);
        station.setStationCode(code);
        station.setStationName(name);
        station.setCity(city);
        if (id != null) {
            station.setState("Test State");
        }
        return station;
    }

    private RailRadarStationMapping mapping(List<Station> stations) {
        return new RailRadarStationMapping(stations.size(), 0, stations, List.of());
    }

    private TrainStop stop(
            TrainRoute route,
            Station station,
            int sequence,
            LocalTime arrival,
            LocalTime departure,
            int dayOffset) {
        TrainStop stop = new TrainStop();
        stop.setTrainRoute(route);
        stop.setStation(station);
        stop.setStopSequence(sequence);
        stop.setArrivalTime(arrival);
        stop.setDepartureTime(departure);
        stop.setDayOffset(dayOffset);
        return stop;
    }
}
