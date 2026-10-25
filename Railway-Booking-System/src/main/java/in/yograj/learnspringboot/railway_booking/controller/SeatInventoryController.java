package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.HoldSeatRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.SeatAvailabilityResponseDTO;
import in.yograj.learnspringboot.railway_booking.service.SeatInventoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/journeys")
public class SeatInventoryController {

    private final SeatInventoryService seatInventoryService;

    public SeatInventoryController(SeatInventoryService seatInventoryService) {
        this.seatInventoryService = seatInventoryService;
    }

    @GetMapping("/{journeyId}/seats")
    public List<SeatAvailabilityResponseDTO> getJourneySeats(
            @PathVariable Long journeyId,
            @RequestParam(required = false) Long boardingJourneyStopId,
            @RequestParam(required = false) Long alightingJourneyStopId) {
        return seatInventoryService.getJourneySeatAvailability(
                journeyId, boardingJourneyStopId, alightingJourneyStopId);
    }

    @GetMapping("/{journeyId}/seats/available")
    public List<SeatAvailabilityResponseDTO> getAvailableSeats(
            @PathVariable Long journeyId,
            @RequestParam(required = false) Long boardingJourneyStopId,
            @RequestParam(required = false) Long alightingJourneyStopId) {
        return seatInventoryService.getAvailableSeats(
                journeyId, boardingJourneyStopId, alightingJourneyStopId);
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{journeyId}/seats/hold")
    public ResponseEntity<SeatAvailabilityResponseDTO> holdSeat(@PathVariable Long journeyId,
                                                               @Valid @RequestBody HoldSeatRequestDTO request,
                                                               Authentication authentication) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(seatInventoryService.holdSeat(
                        journeyId, request.seatId(), request.holdMinutes(),
                        request.boardingJourneyStopId(), request.alightingJourneyStopId(),
                        authentication.getName()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{journeyId}/seats/release")
    public ResponseEntity<SeatAvailabilityResponseDTO> releaseSeat(@PathVariable Long journeyId,
                                                                  @RequestBody HoldSeatRequestDTO request) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(seatInventoryService.releaseSeat(journeyId, request.seatId()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{journeyId}/seats/seed")
    public ResponseEntity<List<SeatAvailabilityResponseDTO>> seedInventory(@PathVariable Long journeyId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(seatInventoryService.seedInventoryForJourney(journeyId));
    }
}
