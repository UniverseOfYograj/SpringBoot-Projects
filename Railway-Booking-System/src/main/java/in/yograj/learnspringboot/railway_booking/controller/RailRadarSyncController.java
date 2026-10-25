package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarSyncService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.StationSyncRequestDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.StationSyncResponseDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.TrainSyncResponseDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/sync/railradar")
public class RailRadarSyncController {

    private final RailRadarSyncService syncService;

    public RailRadarSyncController(RailRadarSyncService syncService) {
        this.syncService = syncService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/stations")
    public StationSyncResponseDTO syncStations(@Valid @RequestBody StationSyncRequestDTO request) {
        return syncService.syncStations(request);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/trains/{trainNumber}")
    public TrainSyncResponseDTO syncTrain(
            @PathVariable @Pattern(regexp = "\\d{5}", message = "Train number must contain five digits")
            String trainNumber) {
        return syncService.syncTrain(trainNumber);
    }
}
