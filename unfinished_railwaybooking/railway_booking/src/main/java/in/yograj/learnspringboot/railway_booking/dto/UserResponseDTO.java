package in.yograj.learnspringboot.railway_booking.dto;

import java.time.LocalDateTime;

public record UserResponseDTO(
        Long userId,
        String username,
        String fullName,
        String email,
        String phoneNumber,
        String status,
        LocalDateTime createdAt
) {}