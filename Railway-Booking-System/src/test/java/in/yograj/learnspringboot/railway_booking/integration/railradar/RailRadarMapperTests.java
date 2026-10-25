package in.yograj.learnspringboot.railway_booking.integration.railradar;

import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.RailRadarStationDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.RailRadarTrainDetailsDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.exception.RailRadarSyncException;
import in.yograj.learnspringboot.railway_booking.integration.railradar.mapper.RailRadarMapper;
import in.yograj.learnspringboot.railway_booking.integration.railradar.mapper.RailRadarTrainImport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RailRadarMapperTests {

    private final RailRadarMapper mapper = new RailRadarMapper();

    @Test
    void mapsMultipleStationResultsWithoutInventingMissingStates() {
        var mapping = mapper.mapStations(List.of(
                new RailRadarStationDTO("ndls", "New Delhi", "New Delhi"),
                new RailRadarStationDTO("STA", "Satna", "Satna"),
                new RailRadarStationDTO("SRM", "Sarmatanr", "Satna")));

        assertEquals(3, mapping.fetched());
        assertEquals(0, mapping.skipped());
        assertEquals(3, mapping.stations().size());
        assertEquals("NDLS", mapping.stations().get(0).getStationCode());
        assertNull(mapping.stations().get(0).getState());
        assertNull(mapping.stations().get(1).getState());
        assertTrue(mapping.unresolved().isEmpty());
    }

    @Test
    void acceptsAValidSearchWithNoMatches() {
        assertTrue(mapper.mapStations(List.of()).stations().isEmpty());
    }

    @Test
    void collectsUnresolvedAndConflictingCodesInsteadOfFailingAtFirstStation() {
        var mapping = mapper.mapStations(List.of(
                new RailRadarStationDTO("NDLS", "New Delhi", "New Delhi"),
                new RailRadarStationDTO("STA", "Satna", "Satna"),
                new RailRadarStationDTO("STA", "Different name", "Satna"),
                new RailRadarStationDTO("BPL", null, "Bhopal")));

        assertEquals(4, mapping.fetched());
        assertEquals(2, mapping.skipped());
        assertEquals(2, mapping.stations().size());
        assertEquals("NDLS", mapping.stations().getFirst().getStationCode());
        assertEquals("BPL", mapping.stations().get(1).getStationCode());
        assertNull(mapping.stations().get(1).getStationName());
        assertEquals(1, mapping.unresolved().size());
        assertEquals("STA", mapping.unresolved().getFirst().stationCode());
    }

    @Test
    void coalescesIdenticalDuplicateStationResults() {
        var mapping = mapper.mapStations(List.of(
                new RailRadarStationDTO("NDLS", "New Delhi", "New Delhi"),
                new RailRadarStationDTO(" ndls ", "New Delhi", "New Delhi")));

        assertEquals(2, mapping.fetched());
        assertEquals(1, mapping.skipped());
        assertEquals(1, mapping.stations().size());
        assertTrue(mapping.unresolved().isEmpty());
    }

    @Test
    void mapsOrderedTrainStopsAndOvernightDayOffsets() {
        RailRadarTrainDetailsDTO details = new RailRadarTrainDetailsDTO(
                new RailRadarTrainDetailsDTO.TrainInfo("12919", "Malwa Express"),
                List.of(
                        stop(1, "INDB", "Source", "23:30", 1, 1),
                        stop(2, "DWD", "00:30", "00:35", 2, 2),
                        stop(3, "RTM", "05:30", "Destination", 2, 2)));

        var imported = mapper.mapTrain("12919", details);

        assertEquals("12919", imported.trainNumber());
        assertEquals("Malwa Express", imported.trainName());
        assertEquals(3, imported.stops().size());
        assertEquals(LocalTime.of(23, 30), imported.stops().getFirst().departureTime());
        assertEquals(0, imported.stops().getFirst().dayOffset());
        assertEquals(1, imported.stops().get(1).dayOffset());
        assertEquals(LocalTime.of(5, 30), imported.stops().getLast().arrivalTime());
    }

    @Test
    void normalizesMultiDayRouteRelativeToFirstStopWithoutFlatteningIt() {
        RailRadarTrainDetailsDTO details = new RailRadarTrainDetailsDTO(
                new RailRadarTrainDetailsDTO.TrainInfo("12919", "Malwa Express"),
                List.of(
                        stop(1, "INDB", "Source", "23:30", 4, 4),
                        stop(2, "DWD", "00:30", "00:35", 5, 5),
                        stop(3, "RTM", "05:30", "05:35", 6, 6),
                        stop(4, "BCT", "08:00", "Destination", 7, 7)));

        var imported = mapper.mapTrain("12919", details);

        assertEquals(List.of(0, 1, 2, 3),
                imported.stops().stream().map(RailRadarTrainImport.Stop::dayOffset).toList());
    }

    @Test
    void rejectsStopArrivalAndDepartureOnDifferentDays() {
        RailRadarTrainDetailsDTO details = new RailRadarTrainDetailsDTO(
                new RailRadarTrainDetailsDTO.TrainInfo("12919", "Malwa Express"),
                List.of(
                        stop(1, "INDB", "Source", "23:30", 0, 0),
                        stop(2, "DWD", "23:50", "00:10", 0, 1)));

        RailRadarSyncException exception = assertThrows(RailRadarSyncException.class,
                () -> mapper.mapTrain("12919", details));
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    private RailRadarTrainDetailsDTO.RouteStop stop(
            int sequence,
            String stationCode,
            String arrival,
            String departure,
            int arrivalDay,
            int departureDay) {
        return new RailRadarTrainDetailsDTO.RouteStop(sequence,
                new RailRadarTrainDetailsDTO.StationInfo(stationCode, stationCode),
                arrival, departure, arrivalDay, departureDay);
    }
}
