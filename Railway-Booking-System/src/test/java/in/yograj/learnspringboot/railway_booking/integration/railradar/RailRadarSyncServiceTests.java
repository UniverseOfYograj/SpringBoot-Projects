package in.yograj.learnspringboot.railway_booking.integration.railradar;

import in.yograj.learnspringboot.railway_booking.entity.Station;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.RailRadarStationDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.RailRadarTrainDetailsDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.TrainSyncResponseDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.exception.RailRadarSyncException;
import in.yograj.learnspringboot.railway_booking.integration.railradar.mapper.RailRadarMapper;
import in.yograj.learnspringboot.railway_booking.repository.StationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RailRadarSyncServiceTests {

    @Test
    void resolvesOnlyAnExactCaseInsensitiveStationCodeMatch() {
        RailRadarClient client = mock(RailRadarClient.class);
        RailRadarPersistenceService persistenceService = mock(RailRadarPersistenceService.class);
        StationRepository stationRepository = mock(StationRepository.class);
        when(client.getTrainDetails("12919")).thenReturn(trainDetails("DADN"));
        when(client.searchStations("DADN", 10)).thenReturn(List.of(
                new RailRadarStationDTO("DAD", "Wrong station", "Indore"),
                new RailRadarStationDTO("dadn", "Dr. Ambedkar Nagar", "Indore")));
        when(stationRepository.findByStationCode("DADN")).thenReturn(Optional.empty());
        when(stationRepository.findByStationCode("BCT"))
                .thenReturn(Optional.of(station("BCT", "Mumbai Central", "Mumbai")));
        when(persistenceService.persistTrain(org.mockito.ArgumentMatchers.any(), anyList()))
                .thenAnswer(invocation -> {
                    List<Station> stations = invocation.getArgument(1);
                    assertEquals(1, stations.size());
                    assertEquals("DADN", stations.getFirst().getStationCode());
                    assertEquals("Dr. Ambedkar Nagar", stations.getFirst().getStationName());
                    assertEquals("Indore", stations.getFirst().getCity());
                    return new TrainSyncResponseDTO("12919", 1L, "RailRadar timetable", 1, 0, 2, false);
                });

        RailRadarSyncService service = new RailRadarSyncService(
                client, new RailRadarMapper(), persistenceService, stationRepository);
        service.syncTrain("12919");

        verify(client).searchStations("DADN", 10);
        verify(persistenceService).persistTrain(org.mockito.ArgumentMatchers.any(), anyList());
    }

    @Test
    void failsClearlyWhenStationSearchHasNoExactCodeMatch() {
        RailRadarClient client = mock(RailRadarClient.class);
        RailRadarPersistenceService persistenceService = mock(RailRadarPersistenceService.class);
        StationRepository stationRepository = mock(StationRepository.class);
        when(client.getTrainDetails("12919")).thenReturn(trainDetails("DADN"));
        when(client.searchStations("DADN", 10)).thenReturn(List.of(
                new RailRadarStationDTO("DAD", "Wrong station", "Indore")));
        when(stationRepository.findByStationCode("DADN")).thenReturn(Optional.empty());

        RailRadarSyncService service = new RailRadarSyncService(
                client, new RailRadarMapper(), persistenceService, stationRepository);
        RailRadarSyncException exception = assertThrows(
                RailRadarSyncException.class, () -> service.syncTrain("12919"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
        assertEquals("RailRadar station search returned no exact match for route station code: DADN",
                exception.getMessage());
        verify(persistenceService, never()).persistTrain(org.mockito.ArgumentMatchers.any(), anyList());
    }

    private RailRadarTrainDetailsDTO trainDetails(String stationCode) {
        return new RailRadarTrainDetailsDTO(
                new RailRadarTrainDetailsDTO.TrainInfo("12919", "Malwa Express"),
                List.of(
                        new RailRadarTrainDetailsDTO.RouteStop(1,
                                new RailRadarTrainDetailsDTO.StationInfo(stationCode, stationCode),
                                null, "20:00", 0, 0),
                        new RailRadarTrainDetailsDTO.RouteStop(2,
                                new RailRadarTrainDetailsDTO.StationInfo("BCT", "Mumbai Central"),
                                "08:00", null, 1, 1)));
    }

    private Station station(String code, String name, String city) {
        Station station = new Station();
        station.setStationCode(code);
        station.setStationName(name);
        station.setCity(city);
        return station;
    }
}
