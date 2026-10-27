package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.TatkalQueueResponse;
import in.yograj.learnspringboot.railway_booking.service.TatkalQueueService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tatkal/journeys/{journeyId}/queue")
@PreAuthorize("isAuthenticated()")
public class TatkalQueueController {
    private final TatkalQueueService queueService;

    public TatkalQueueController(TatkalQueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping
    public ResponseEntity<TatkalQueueResponse> join(@PathVariable long journeyId,
                                                     Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(queueService.join(journeyId, authentication.getName()));
    }

    @GetMapping("/me")
    public TatkalQueueResponse status(@PathVariable long journeyId, Authentication authentication) {
        return queueService.status(journeyId, authentication.getName());
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> leave(@PathVariable long journeyId, Authentication authentication) {
        queueService.leave(journeyId, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
