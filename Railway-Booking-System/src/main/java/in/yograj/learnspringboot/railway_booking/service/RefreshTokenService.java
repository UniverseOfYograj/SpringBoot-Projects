package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.entity.RefreshToken;
import in.yograj.learnspringboot.railway_booking.entity.User;
import in.yograj.learnspringboot.railway_booking.repository.RefreshTokenRepository;
import in.yograj.learnspringboot.railway_booking.repository.UserRepository;
import in.yograj.learnspringboot.railway_booking.security.JwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class RefreshTokenService {

    public record TokenRotationResult(User user, String newRefreshToken) {}

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final UserRepository userRepository;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, JwtService jwtService) {
        this(refreshTokenRepository, jwtService, null);
    }

    @Autowired
    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               JwtService jwtService,
                               UserRepository userRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
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

    /**
     * Rotates a refresh token:
     * 1. Validates the presented token.
     * 2. If already revoked or expired, detects token reuse, revokes all tokens for the user family, and aborts.
     * 3. Marks the old token as revoked to detect future reuse attempts.
     * 4. Issues and saves a fresh, single-use refresh token.
     */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public TokenRotationResult rotateRefreshToken(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            throw new IllegalArgumentException("Refresh token is required");
        }

        RefreshToken refreshToken = refreshTokenRepository.findByTokenForUpdate(tokenValue)
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

        if (refreshToken.isRevoked() || refreshToken.getExpiresAt().isBefore(Instant.now())) {
            revokeAllForUser(refreshToken.getUser());
            throw new IllegalArgumentException("Refresh token expired or revoked");
        }

        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);

        RefreshToken newRefreshToken = createRefreshToken(refreshToken.getUser());
        return new TokenRotationResult(refreshToken.getUser(), newRefreshToken.getToken());
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public User validateAndGetUser(String tokenValue) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

        if (refreshToken.isRevoked() || refreshToken.getExpiresAt().isBefore(Instant.now())) {
            revokeAllForUser(refreshToken.getUser());
            throw new IllegalArgumentException("Refresh token expired or revoked");
        }

        return refreshToken.getUser();
    }

    @Transactional
    public void revokeToken(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            return;
        }
        refreshTokenRepository.findByToken(tokenValue).ifPresent(token -> {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
        });
    }

    @Transactional
    public void revokeAllForUsername(String username) {
        if (username == null || username.isBlank() || userRepository == null) {
            return;
        }
        userRepository.findByUsername(username).ifPresent(this::revokeAllForUser);
    }

    @Transactional
    public void revokeAllForUser(User user) {
        refreshTokenRepository.deleteByUser(user);
    }

    public String generateUniqueToken() {
        return UUID.randomUUID().toString();
    }
}
