package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.CoachTopologyDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainTopologyDTO;
import in.yograj.learnspringboot.railway_booking.service.TrainTopologyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller exposing static train and coach layout (topology).
 * Returns physical coach composition and seat numbering.
 * Authoritative dynamic seat availability must continue to be queried via /api/journeys/{journeyId}/seats.
 */
@RestController
@RequestMapping("/api")
public class TrainTopologyController {

    private final TrainTopologyService trainTopologyService;

    public TrainTopologyController(TrainTopologyService trainTopologyService) {
        this.trainTopologyService = trainTopologyService;
    }

    @GetMapping({"/trains/{trainId}/layout", "/trains/{trainId}/topology"})
    public ResponseEntity<TrainTopologyDTO> getTrainTopology(@PathVariable Long trainId) {
        return ResponseEntity.ok(trainTopologyService.getTrainTopology(trainId));
    }

    @GetMapping({"/trains/coaches/{coachId}/layout", "/trains/coaches/{coachId}/topology",
                 "/coaches/{coachId}/layout", "/coaches/{coachId}/topology"})
    public ResponseEntity<CoachTopologyDTO> getCoachTopology(@PathVariable Long coachId) {
        return ResponseEntity.ok(trainTopologyService.getCoachTopology(coachId));
    }
}
