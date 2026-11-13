package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.TrainDetailResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainSearchResponseDTO;
import in.yograj.learnspringboot.railway_booking.service.TrainSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/trains")
public class TrainSearchController {

    private final TrainSearchService trainSearchService;

    public TrainSearchController(TrainSearchService trainSearchService) {
        this.trainSearchService = trainSearchService;
    }

    @GetMapping("/search")
    public List<TrainSearchResponseDTO> searchTrains(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String effectiveQuery = (q != null && !q.isBlank()) ? q : query;

        // If from and to are both provided and no query is specified, use existing direct search
        if (from != null && !from.isBlank() && to != null && !to.isBlank()
                && (effectiveQuery == null || effectiveQuery.isBlank())) {
            return trainSearchService.searchTrains(from, to);
        }

        // Unified search handles station pairs, query filtering, and train number/name searches
        return trainSearchService.searchTrainsUnified(effectiveQuery, from, to, page, size);
    }

    @GetMapping("/{trainNumber}")
    public ResponseEntity<TrainDetailResponseDTO> getTrainDetails(@PathVariable String trainNumber) {
        return ResponseEntity.ok(trainSearchService.getTrainByNumber(trainNumber));
    }
}
