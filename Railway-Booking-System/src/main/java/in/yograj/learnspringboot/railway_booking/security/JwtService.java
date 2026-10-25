package in.yograj.learnspringboot.railway_booking.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.function.Function;

@Service
public class JwtService implements InitializingBean {

    private static final int MIN_SECRET_BYTE_LENGTH = 32;

    @Value("${jwt.access-secret:}")
    private String accessSecret;

    @Value("${jwt.refresh-secret:}")
    private String refreshSecret;

    @Value("${jwt.access-expiration-ms:900000}")
    private long accessExpirationMs;

    @Value("${jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs;

    public JwtService() {
    }

    public JwtService(String accessSecret, String refreshSecret, long accessExpirationMs, long refreshExpirationMs) {
        this.accessSecret = accessSecret;
        this.refreshSecret = refreshSecret;
        this.accessExpirationMs = accessExpirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
        validateSecrets();
    }

    @Override
    public void afterPropertiesSet() {
        validateSecrets();
    }

    public void validateSecrets() {
        validateSecret(accessSecret, "access");
        validateSecret(refreshSecret, "refresh");
    }

    private void validateSecret(String secret, String secretType) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT " + secretType + " secret is missing. "
                    + "Please configure 'jwt." + secretType + "-secret' via environment variables or application properties.");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTE_LENGTH) {
            throw new IllegalStateException("JWT " + secretType + " secret is too short ("
                    + keyBytes.length + " bytes). "
                    + "The HMAC-SHA algorithm requires at least " + MIN_SECRET_BYTE_LENGTH + " bytes (256 bits).");
        }
    }

    public String generateAccessToken(String username, String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("User role is required to generate an access token");
        }
        return buildToken(username, role, accessSecret, accessExpirationMs);
    }

    public String generateRefreshToken(String username) {
        return buildToken(username, null, refreshSecret, refreshExpirationMs);
    }

    public String extractUsernameFromAccessToken(String token) {
        return extractClaim(token, accessSecret, Claims::getSubject);
    }

    public String extractUsernameFromRefreshToken(String token) {
        return extractClaim(token, refreshSecret, Claims::getSubject);
    }

    public boolean isAccessTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsernameFromAccessToken(token);
        return username.equals(userDetails.getUsername()) && !isAccessTokenExpired(token);
    }

    public boolean isRefreshTokenExpired(String token) {
        return extractExpiration(token, refreshSecret).before(new Date());
    }

    public boolean isAccessTokenExpired(String token) {
        return extractExpiration(token, accessSecret).before(new Date());
    }

    public long getAccessTokenExpirationMs() {
        return accessExpirationMs;
    }

    public long getRefreshTokenExpirationMs() {
        return refreshExpirationMs;
    }

    private String buildToken(String username, String role, String secret, long expirationMs) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        var tokenBuilder = Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiry);
        if (role != null) {
            tokenBuilder.claim("role", role);
        }
        return tokenBuilder.signWith(getSigningKey(secret)).compact();
    }

    private Date extractExpiration(String token, String secret) {
        return extractClaim(token, secret, Claims::getExpiration);
    }

    private <T> T extractClaim(String token, String secret, Function<Claims, T> claimsResolver) {
        final Claims claims = Jwts.parser()
                .verifyWith(getSigningKey(secret))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claimsResolver.apply(claims);
    }

    private SecretKey getSigningKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT signing secret is missing or empty");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTE_LENGTH) {
            throw new IllegalStateException("JWT signing secret is too short ("
                    + keyBytes.length + " bytes). The HMAC-SHA algorithm requires at least "
                    + MIN_SECRET_BYTE_LENGTH + " bytes (256 bits).");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
