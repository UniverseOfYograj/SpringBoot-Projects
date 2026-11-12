package in.yograj.learnspringboot.railway_booking.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record FareQuoteRequestDTO(
        @NotNull(message = "journeyId is required")
        Long journeyId,

        @NotBlank(message = "travelClass is required")
        String travelClass,

        @NotNull(message = "passengerCount is required")
        @Min(value = 1, message = "passengerCount must be at least 1")
        @Max(value = 6, message = "passengerCount must not exceed 6")
        Integer passengerCount,

        Long boardingJourneyStopId,

        Long alightingJourneyStopId
) {
}
