package in.yograj.learnspringboot.railway_booking.security;

import in.yograj.learnspringboot.railway_booking.controller.AuthController;
import in.yograj.learnspringboot.railway_booking.dto.AuthResponse;
import in.yograj.learnspringboot.railway_booking.dto.LoginRequest;
import in.yograj.learnspringboot.railway_booking.dto.TokenRefreshRequest;
import in.yograj.learnspringboot.railway_booking.entity.RefreshToken;
import in.yograj.learnspringboot.railway_booking.entity.User;
import in.yograj.learnspringboot.railway_booking.repository.UserRepository;
import in.yograj.learnspringboot.railway_booking.service.RefreshTokenService;
import in.yograj.learnspringboot.railway_booking.service.UserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthTokenRoleTests {

    private static final String ACCESS_SECRET = "test-access-signing-secret-at-least-32-characters";

    private AuthenticationManager authenticationManager;
    private JwtService jwtService;
    private UserRepository userRepository;
    private RefreshTokenService refreshTokenService;
    private AuthController authController;

    @BeforeEach
    void setUp() {
        authenticationManager = mock(AuthenticationManager.class);
        jwtService = new JwtService(
                ACCESS_SECRET,
                "test-refresh-signing-secret-at-least-32-characters",
                900_000L,
                604_800_000L);
        userRepository = mock(UserRepository.class);
        refreshTokenService = mock(RefreshTokenService.class);
        authController = new AuthController(
                authenticationManager,
                jwtService,
                mock(UserService.class),
                userRepository,
                refreshTokenService);
    }

    @Test
    void loginAccessTokenIncludesAdminRoleAndExistingStandardClaims() {
        assertLoginRole("admin", "ROLE_ADMIN");
    }

    @Test
    void loginAccessTokenIncludesUserRole() {
        assertLoginRole("passenger", "ROLE_USER");
    }

    @Test
    void loginWithEmailSucceedsAndGeneratesAccessTokenForCanonicalUsername() {
        String username = "passenger";
        String email = "passenger@example.com";
        String role = "ROLE_USER";
        User user = user(username, role);
        user.setEmail(email);

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority(role)));
        when(authenticationManager.authenticate(any(Authentication.class))).thenReturn(authentication);
        when(userRepository.findByUsername(email)).thenReturn(Optional.empty());
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setToken("refresh-token-email");
        when(refreshTokenService.createRefreshToken(user)).thenReturn(refreshToken);

        AuthResponse response = authController.login(new LoginRequest(email, "secret-pass")).getBody();

        assertNotNull(response);
        assertEquals("Bearer", response.tokenType());
        assertEquals("refresh-token-email", response.refreshToken());
        Claims claims = accessClaims(response.accessToken());
        assertEquals(username, claims.getSubject());
        assertEquals(role, claims.get("role", String.class));
    }

    @Test
    void loginWithInvalidCredentialsThrowsBadCredentialsException() {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThrows(BadCredentialsException.class,
                () -> authController.login(new LoginRequest("passenger", "wrong-password")));
    }

    @Test
    void loginWithUnknownUserThrowsBadCredentialsException() {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThrows(BadCredentialsException.class,
                () -> authController.login(new LoginRequest("unknown@example.com", "any-password")));
    }

    @Test
    void refreshedAccessTokenUsesUsersCurrentPersistedRole() {
        User user = user("admin", "ROLE_ADMIN");
        when(refreshTokenService.validateAndGetUser("refresh-token"))
                .thenReturn(user);

        AuthResponse response = authController.refresh(new TokenRefreshRequest("refresh-token")).getBody();

        assertNotNull(response);
        Claims claims = accessClaims(response.accessToken());
        assertEquals("admin", claims.getSubject());
        assertEquals("ROLE_ADMIN", claims.get("role", String.class));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
    }

    @Test
    void loginRejectsAuthenticatedPrincipalWithoutExactlyOneRoleAuthority() {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "passenger", null, List.of());
        when(authenticationManager.authenticate(any(Authentication.class))).thenReturn(authentication);
        when(userRepository.findByUsername("passenger"))
                .thenReturn(Optional.of(user("passenger", "ROLE_USER")));

        assertThrows(IllegalStateException.class,
                () -> authController.login(new LoginRequest("passenger", "not-logged")));
    }

    private void assertLoginRole(String username, String role) {
        User user = user(username, role);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority(role)));
        when(authenticationManager.authenticate(any(Authentication.class))).thenReturn(authentication);
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setToken("refresh-token");
        when(refreshTokenService.createRefreshToken(user)).thenReturn(refreshToken);

        AuthResponse response = authController.login(new LoginRequest(username, "not-logged")).getBody();

        assertNotNull(response);
        Claims claims = accessClaims(response.accessToken());
        assertEquals(username, claims.getSubject());
        assertEquals(role, claims.get("role", String.class));
        org.junit.jupiter.api.Assertions.assertNull(claims.get("roles"));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
        assertEquals(900_000L,
                claims.getExpiration().getTime() - claims.getIssuedAt().getTime());
    }

    private User user(String username, String role) {
        User user = new User();
        user.setUsername(username);
        user.setRole(role);
        return user;
    }

    private Claims accessClaims(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(ACCESS_SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
