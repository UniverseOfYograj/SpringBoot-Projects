package in.yograj.learnspringboot.railway_booking.integration.railradar.mapper;

import in.yograj.learnspringboot.railway_booking.entity.Station;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.UnresolvedStationDTO;

import java.util.List;

public record RailRadarStationMapping(
        int fetched,
        int skipped,
        List<Station> stations,
        List<UnresolvedStationDTO> unresolved
) {
}
