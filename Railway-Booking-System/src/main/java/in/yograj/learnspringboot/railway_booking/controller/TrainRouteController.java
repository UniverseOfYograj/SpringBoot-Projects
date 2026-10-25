package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.CreateTrainRouteRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainRouteResponseDTO;
import in.yograj.learnspringboot.railway_booking.service.TrainRouteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/trains/{trainId}/routes")
public class TrainRouteController {

    private final TrainRouteService trainRouteService;

    public TrainRouteController(TrainRouteService trainRouteService) {
        this.trainRouteService = trainRouteService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<TrainRouteResponseDTO> createRoute(
            @PathVariable Long trainId,
            @Valid @RequestBody CreateTrainRouteRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(trainRouteService.createRoute(trainId, request));
    }

    @GetMapping
    public List<TrainRouteResponseDTO> getRoutes(@PathVariable Long trainId) {
        return trainRouteService.getRoutes(trainId);
    }
}
