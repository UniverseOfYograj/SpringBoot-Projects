package in.yograj.learnspringboot.railway_booking.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

public record JourneyRequestDTO(
        @NotNull(message = "Train ID is required") Long trainId,
        Long fromStationId,
        Long toStationId,
        @NotNull(message = "Journey date is required") LocalDate journeyDate,
        LocalTime departureTime,
        LocalTime arrivalTime,
        Long trainRouteId
) {
    @AssertTrue(message = "Either trainRouteId or both fromStationId and toStationId must be provided")
    public boolean isValidRouteOrStationSpecification() {
        if (trainRouteId != null) {
            return true;
        }
        return fromStationId != null && toStationId != null;
    }

    @AssertTrue(message = "Source and destination stations cannot be the same")
    public boolean isDifferentStations() {
        if (fromStationId != null && toStationId != null) {
            return !fromStationId.equals(toStationId);
        }
        return true;
    }

    @AssertTrue(message = "Departure and arrival times are required for station-based journeys")
    public boolean hasTimesForStationBasedJourney() {
        if (trainRouteId != null) {
            return true;
        }
        if (fromStationId != null && toStationId != null) {
            return departureTime != null && arrivalTime != null;
        }
        return true;
    }
}
