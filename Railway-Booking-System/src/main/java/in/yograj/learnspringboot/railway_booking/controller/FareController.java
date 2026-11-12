package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.FareQuoteRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.FareQuoteResponseDTO;
import in.yograj.learnspringboot.railway_booking.service.FareCalculationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fare")
public class FareController {

    private final FareCalculationService fareCalculationService;

    public FareController(FareCalculationService fareCalculationService) {
        this.fareCalculationService = fareCalculationService;
    }

    @GetMapping("/quote")
    public FareQuoteResponseDTO getFareQuote(
            @RequestParam Long journeyId,
            @RequestParam String travelClass,
            @RequestParam(defaultValue = "1") Integer passengerCount,
            @RequestParam(required = false) Long boardingJourneyStopId,
            @RequestParam(required = false) Long alightingJourneyStopId) {
        return fareCalculationService.calculateFareQuote(
                journeyId, travelClass, passengerCount, boardingJourneyStopId, alightingJourneyStopId);
    }

    @PostMapping("/quote")
    public FareQuoteResponseDTO postFareQuote(@Valid @RequestBody FareQuoteRequestDTO request) {
        return fareCalculationService.calculateFareQuote(
                request.journeyId(),
                request.travelClass(),
                request.passengerCount(),
                request.boardingJourneyStopId(),
                request.alightingJourneyStopId());
    }
}
