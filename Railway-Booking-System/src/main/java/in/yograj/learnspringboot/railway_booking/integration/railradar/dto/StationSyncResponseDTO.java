package in.yograj.learnspringboot.railway_booking.integration.railradar.dto;

import java.util.List;

public record StationSyncResponseDTO(
        int fetched,
        int inserted,
        int updated,
        int unchanged,
        int skipped,
        List<UnresolvedStationDTO> unresolved
) {
}
