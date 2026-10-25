package in.yograj.learnspringboot.railway_booking.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        String message
) {}
