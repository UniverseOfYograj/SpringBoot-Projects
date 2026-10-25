package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.UserResponseDTO;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.service.UserService;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserService userService;
    private final GitHubRailwayDataImportService railwayDataImportService;

    public AdminController(
            UserService userService,
            GitHubRailwayDataImportService railwayDataImportService) {
        this.userService = userService;
        this.railwayDataImportService = railwayDataImportService;
    }

    @GetMapping("/users")
    public List<UserResponseDTO> getAllUsers() {
        return userService.getAllUsers();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/import/github-railway")
    public GitHubRailwayDataImportService.ImportResult importRailwayData(
            @RequestParam(defaultValue = "1") @Min(1) int startFrom,
            @RequestParam(defaultValue = "1") @Min(1) int maxTrains) {

        return railwayDataImportService.importData(startFrom, maxTrains);
    }
}