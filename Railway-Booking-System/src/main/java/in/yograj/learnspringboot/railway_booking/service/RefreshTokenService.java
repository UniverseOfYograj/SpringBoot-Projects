package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.entity.RefreshToken;
import in.yograj.learnspringboot.railway_booking.entity.User;
import in.yograj.learnspringboot.railway_booking.repository.RefreshTokenRepository;
import in.yograj.learnspringboot.railway_booking.security.JwtService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class RefreshTokenService {

    // Refresh token service ek short-lived access token aur long-lived refresh token ka lifecycle handle karta hai.
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, JwtService jwtService) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
    }

    public RefreshToken createRefreshToken(User user) {
        String tokenValue = jwtService.generateRefreshToken(user.getUsername());
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setToken(tokenValue);
        refreshToken.setUser(user);
        refreshToken.setExpiresAt(Instant.now().plusMillis(jwtService.getRefreshTokenExpirationMs()));
        refreshToken.setRevoked(false);
        return refreshTokenRepository.save(refreshToken);
    }

    @Transactional
    public User validateAndGetUser(String tokenValue) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

        if (refreshToken.isRevoked() || refreshToken.getExpiresAt().isBefore(Instant.now())) {
            revokeAllForUser(refreshToken.getUser());
            throw new IllegalArgumentException("Refresh token expired or revoked");
        }

        String username = jwtService.extractUsernameFromRefreshToken(tokenValue);
        return refreshToken.getUser();
    }

    @Transactional
    public void revokeAllForUser(User user) {
        refreshTokenRepository.deleteByUser(user);
    }

    public String generateUniqueToken() {
        return UUID.randomUUID().toString();
    }
}
