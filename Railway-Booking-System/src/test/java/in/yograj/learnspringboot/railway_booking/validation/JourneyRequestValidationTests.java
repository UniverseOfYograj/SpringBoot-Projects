package in.yograj.learnspringboot.railway_booking.validation;

import in.yograj.learnspringboot.railway_booking.dto.JourneyRequestDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JourneyRequestValidationTests {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("Valid route-based journey with null station IDs passes validation")
    void validRouteBasedJourneyPassesValidation() {
        JourneyRequestDTO dto = new JourneyRequestDTO(
                1L,
                null,
                null,
                LocalDate.now().plusDays(3),
                null,
                null,
                50L
        );

        Set<ConstraintViolation<JourneyRequestDTO>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Route-based journey should have no violations");
    }

    @Test
    @DisplayName("Valid station-based journey with explicit stations and times passes validation")
    void validStationBasedJourneyPassesValidation() {
        JourneyRequestDTO dto = new JourneyRequestDTO(
                1L,
                10L,
                20L,
                LocalDate.now().plusDays(3),
                LocalTime.of(8, 30),
                LocalTime.of(14, 0),
                null
        );

        Set<ConstraintViolation<JourneyRequestDTO>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Station-based journey should have no violations");
    }

    @Test
    @DisplayName("Missing trainId fails validation")
    void missingTrainIdFailsValidation() {
        JourneyRequestDTO dto = new JourneyRequestDTO(
                null,
                null,
                null,
                LocalDate.now().plusDays(3),
                null,
                null,
                50L
        );

        Set<ConstraintViolation<JourneyRequestDTO>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Train ID is required")));
    }

    @Test
    @DisplayName("Missing journeyDate fails validation")
    void missingJourneyDateFailsValidation() {
        JourneyRequestDTO dto = new JourneyRequestDTO(
                1L,
                null,
                null,
                null,
                null,
                null,
                50L
        );

        Set<ConstraintViolation<JourneyRequestDTO>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Journey date is required")));
    }

    @Test
    @DisplayName("Missing both route and stations fails validation")
    void missingBothRouteAndStationsFailsValidation() {
        JourneyRequestDTO dto = new JourneyRequestDTO(
                1L,
                null,
                null,
                LocalDate.now().plusDays(3),
                null,
                null,
                null
        );

        Set<ConstraintViolation<JourneyRequestDTO>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v ->
                v.getMessage().contains("Either trainRouteId or both fromStationId and toStationId must be provided")));
    }

    @Test
    @DisplayName("Missing toStationId in station-based journey fails validation")
    void missingToStationInStationBasedJourneyFailsValidation() {
        JourneyRequestDTO dto = new JourneyRequestDTO(
                1L,
                10L,
                null,
                LocalDate.now().plusDays(3),
                LocalTime.of(8, 0),
                LocalTime.of(12, 0),
                null
        );

        Set<ConstraintViolation<JourneyRequestDTO>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v ->
                v.getMessage().contains("Either trainRouteId or both fromStationId and toStationId must be provided")));
    }

    @Test
    @DisplayName("Same source and destination station fails validation")
    void sameSourceAndDestinationStationFailsValidation() {
        JourneyRequestDTO dto = new JourneyRequestDTO(
                1L,
                10L,
                10L,
                LocalDate.now().plusDays(3),
                LocalTime.of(8, 0),
                LocalTime.of(12, 0),
                null
        );

        Set<ConstraintViolation<JourneyRequestDTO>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v ->
                v.getMessage().contains("Source and destination stations cannot be the same")));
    }

    @Test
    @DisplayName("Station-based journey missing departure or arrival time fails validation")
    void missingTimesInStationBasedJourneyFailsValidation() {
        JourneyRequestDTO dto = new JourneyRequestDTO(
                1L,
                10L,
                20L,
                LocalDate.now().plusDays(3),
                null,
                null,
                null
        );

        Set<ConstraintViolation<JourneyRequestDTO>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v ->
                v.getMessage().contains("Departure and arrival times are required for station-based journeys")));
    }
}
