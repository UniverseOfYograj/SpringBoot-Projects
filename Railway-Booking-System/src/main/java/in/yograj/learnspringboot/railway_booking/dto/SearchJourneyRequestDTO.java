package in.yograj.learnspringboot.railway_booking.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record SearchJourneyRequestDTO(
        @NotNull(message = "From station ID is required") Long fromStationId,
        @NotNull(message = "To station ID is required") Long toStationId,
        @NotNull(message = "Journey date is required") LocalDate journeyDate
) {
}
