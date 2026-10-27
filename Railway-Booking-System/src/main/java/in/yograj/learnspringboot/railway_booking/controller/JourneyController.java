package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.JourneyRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.JourneyResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.JourneyStopResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.SearchJourneyRequestDTO;
import in.yograj.learnspringboot.railway_booking.service.JourneyService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/journeys")
public class JourneyController {

    private final JourneyService journeyService;

    public JourneyController(JourneyService journeyService) {
        this.journeyService = journeyService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<JourneyResponseDTO> createJourney(@Valid @RequestBody JourneyRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(journeyService.createJourney(request));
    }

    @GetMapping
    public List<JourneyResponseDTO> getAllJourneys(
            @RequestParam(required = false) Long trainId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return journeyService.getAllJourneys(trainId, date);
    }

    @GetMapping("/search")
    public List<JourneyResponseDTO> searchJourneys(
            @RequestParam Long fromStationId,
            @RequestParam Long toStationId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate journeyDate) {
        return journeyService.searchJourneys(fromStationId, toStationId, journeyDate);
    }

    @GetMapping("/{id}")
    public ResponseEntity<JourneyResponseDTO> getJourneyById(@PathVariable Long id) {
        return ResponseEntity.ok(journeyService.getJourneyById(id));
    }

    @GetMapping("/{id}/stops")
    public List<JourneyStopResponseDTO> getJourneyStops(@PathVariable Long id) {
        return journeyService.getJourneyStops(id);
    }

    @GetMapping("/filter")
    public List<JourneyResponseDTO> searchJourneys(@Valid @ModelAttribute SearchJourneyRequestDTO request) {
        return journeyService.searchJourneys(request);
    }
}
