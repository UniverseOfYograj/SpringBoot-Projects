package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.TrainSearchResponseDTO;
import in.yograj.learnspringboot.railway_booking.service.TrainSearchService;
import org.springframework.web.bind.annotation.GetMapping;
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
            @RequestParam String from,
            @RequestParam String to) {

        return trainSearchService.searchTrains(from, to);
    }
}