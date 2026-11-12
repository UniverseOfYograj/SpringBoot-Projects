package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.BatchHoldSeatRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.BookingMode;
import in.yograj.learnspringboot.railway_booking.dto.HoldSeatRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.SeatAvailabilityResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.TravelClassAvailabilityDTO;
import in.yograj.learnspringboot.railway_booking.service.SeatInventoryService;
import in.yograj.learnspringboot.railway_booking.service.TatkalAdmissionPassService;
import in.yograj.learnspringboot.railway_booking.service.TatkalJourneyPolicy;
import in.yograj.learnspringboot.railway_booking.service.TatkalQueueService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/journeys")
public class SeatInventoryController {

    private final SeatInventoryService seatInventoryService;
    private final TatkalQueueService tatkalQueueService;
    private final TatkalJourneyPolicy tatkalJourneyPolicy;

    public SeatInventoryController(SeatInventoryService seatInventoryService,
                                   TatkalQueueService tatkalQueueService,
                                   TatkalJourneyPolicy tatkalJourneyPolicy) {
        this.seatInventoryService = seatInventoryService;
        this.tatkalQueueService = tatkalQueueService;
        this.tatkalJourneyPolicy = tatkalJourneyPolicy;
    }

    @GetMapping("/{journeyId}/classes")
    public List<TravelClassAvailabilityDTO> getJourneyClasses(
            @PathVariable Long journeyId,
            @RequestParam(required = false) Long boardingJourneyStopId,
            @RequestParam(required = false) Long alightingJourneyStopId) {
        return seatInventoryService.getJourneyClassesAvailability(
                journeyId, boardingJourneyStopId, alightingJourneyStopId);
    }

    @GetMapping("/{journeyId}/seats")
    public List<SeatAvailabilityResponseDTO> getJourneySeats(
            @PathVariable Long journeyId,
            @RequestParam(required = false) Long boardingJourneyStopId,
            @RequestParam(required = false) Long alightingJourneyStopId,
            @RequestParam(required = false) String travelClass,
            @RequestParam(required = false) String coachNumber) {
        if (travelClass == null && coachNumber == null) {
            return seatInventoryService.getJourneySeatAvailability(
                    journeyId, boardingJourneyStopId, alightingJourneyStopId);
        }
        return seatInventoryService.getJourneySeatAvailability(
                journeyId, boardingJourneyStopId, alightingJourneyStopId, travelClass, coachNumber);
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
                                                               Authentication authentication,
                                                               @RequestHeader(name = "X-Tatkal-Admission", required = false)
                                                               String admissionPass) {
        boolean tatkalJourney = tatkalJourneyPolicy.isTatkalJourney(journeyId);
        if (tatkalJourney && request.bookingMode() != BookingMode.TATKAL) {
            throw new in.yograj.learnspringboot.railway_booking.exception.InvalidAdmissionPassException(
                    "Tatkal journey seat holds require an admitted queue pass");
        }
        if (!tatkalJourney && request.bookingMode() == BookingMode.TATKAL) {
            throw new in.yograj.learnspringboot.railway_booking.exception.InvalidAdmissionPassException(
                    "Tatkal booking mode is not available for this journey");
        }
        if (request.bookingMode() == BookingMode.TATKAL) {
            TatkalAdmissionPassService.AdmissionClaims claims = tatkalQueueService.claimSeatHold(
                    journeyId, authentication.getName(), admissionPass, request.seatId());
            try {
                SeatAvailabilityResponseDTO response;
                if (request.travelClass() != null || request.coachNumber() != null) {
                    response = seatInventoryService.holdSeat(
                            journeyId, request.seatId(), request.holdMinutes(),
                            request.boardingJourneyStopId(), request.alightingJourneyStopId(),
                            request.travelClass(), request.coachNumber(),
                            authentication.getName(), request.bookingMode(), claims.queueEntryId());
                } else {
                    response = seatInventoryService.holdSeat(
                            journeyId, request.seatId(), request.holdMinutes(),
                            request.boardingJourneyStopId(), request.alightingJourneyStopId(),
                            authentication.getName(), request.bookingMode(), claims.queueEntryId());
                }
                tatkalQueueService.completeSeatHold(journeyId, claims.queueEntryId(),
                        authentication.getName(), response.holdExpiresAt());
                return ResponseEntity.ok(response);
            } catch (RuntimeException exception) {
                tatkalQueueService.releaseSeatHoldClaim(journeyId, claims.queueEntryId(),
                        authentication.getName());
                throw exception;
            }
        }

        if (request.travelClass() != null || request.coachNumber() != null) {
            return ResponseEntity.ok(seatInventoryService.holdSeat(
                    journeyId, request.seatId(), request.holdMinutes(),
                    request.boardingJourneyStopId(), request.alightingJourneyStopId(),
                    request.travelClass(), request.coachNumber(),
                    authentication.getName(), request.bookingMode(), null));
        }

        return ResponseEntity.ok(seatInventoryService.holdSeat(
                journeyId, request.seatId(), request.holdMinutes(),
                request.boardingJourneyStopId(), request.alightingJourneyStopId(),
                authentication.getName()));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{journeyId}/seats/hold-batch")
    public ResponseEntity<List<SeatAvailabilityResponseDTO>> holdSeats(
            @PathVariable Long journeyId,
            @Valid @RequestBody BatchHoldSeatRequestDTO request,
            Authentication authentication) {
        boolean tatkalJourney = tatkalJourneyPolicy.isTatkalJourney(journeyId);
        if (tatkalJourney && request.bookingMode() != BookingMode.TATKAL) {
            throw new in.yograj.learnspringboot.railway_booking.exception.InvalidAdmissionPassException(
                    "Tatkal journey seat holds require an admitted queue pass");
        }
        if (!tatkalJourney && request.bookingMode() == BookingMode.TATKAL) {
            throw new in.yograj.learnspringboot.railway_booking.exception.InvalidAdmissionPassException(
                    "Tatkal booking mode is not available for this journey");
        }
        return ResponseEntity.status(HttpStatus.OK)
                .body(seatInventoryService.holdSeatsBatch(
                        journeyId, request.seatIds(), request.holdMinutes(),
                        request.boardingJourneyStopId(), request.alightingJourneyStopId(),
                        request.travelClass(), request.coachNumber(),
                        authentication.getName(), request.bookingMode(), null));
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
