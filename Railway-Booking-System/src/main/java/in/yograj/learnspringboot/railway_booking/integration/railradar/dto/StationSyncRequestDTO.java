package in.yograj.learnspringboot.railway_booking.integration.railradar.dto;

import jakarta.validation.constraints.NotBlank;

public record StationSyncRequestDTO(
        @NotBlank String query,
        Integer limit
) {
}
