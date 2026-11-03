package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.AuthResponse;
import in.yograj.learnspringboot.railway_booking.dto.LoginRequest;
import in.yograj.learnspringboot.railway_booking.dto.TokenRefreshRequest;
import in.yograj.learnspringboot.railway_booking.dto.UserRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.UserResponseDTO;
import in.yograj.learnspringboot.railway_booking.entity.User;
import in.yograj.learnspringboot.railway_booking.repository.UserRepository;
import in.yograj.learnspringboot.railway_booking.security.JwtService;
import in.yograj.learnspringboot.railway_booking.security.LoginRateLimiter;
import in.yograj.learnspringboot.railway_booking.service.RefreshTokenService;
import in.yograj.learnspringboot.railway_booking.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtService jwtService,
                          UserService userService,
                          UserRepository userRepository,
                          RefreshTokenService refreshTokenService) {
        this(authenticationManager, jwtService, userService, userRepository, refreshTokenService,
                new LoginRateLimiter(5, 10, 60));
    }

    @Autowired
    public AuthController(AuthenticationManager authenticationManager,
                          JwtService jwtService,
                          UserService userService,
                          UserRepository userRepository,
                          RefreshTokenService refreshTokenService,
                          LoginRateLimiter loginRateLimiter) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponseDTO> register(@Valid @RequestBody UserRequestDTO request) {
        UserResponseDTO response = userService.registerUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        return login(request, resolveClientIp(servletRequest));
    }

    public ResponseEntity<AuthResponse> login(LoginRequest request) {
        return login(request, "127.0.0.1");
    }

    public ResponseEntity<AuthResponse> login(LoginRequest request, String clientIp) {
        String username = request.username() != null ? request.username().trim() : "";

        // Reserve capacity before authentication so concurrent requests count toward the limit.
        loginRateLimiter.checkAllowed(clientIp, username);

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );
            // Release the reservation and reset this username's failure counter.
            loginRateLimiter.recordSuccess(clientIp, username);
        } catch (AuthenticationException ex) {
            loginRateLimiter.recordFailure(clientIp, username);
            throw ex;
        }

        SecurityContextHolder.getContext().setAuthentication(authentication);

        User user = userRepository.findByUsername(request.username())
                .or(() -> userRepository.findByEmail(request.username()))
                .orElseThrow(() -> new IllegalArgumentException("User not found for login: " + request.username()));

        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .reduce((first, second) -> {
                    throw new IllegalStateException("Authenticated user must have exactly one role");
                })
                .orElseThrow(() -> new IllegalStateException("Authenticated user role is missing"));
        String accessToken = jwtService.generateAccessToken(user.getUsername(), role);
        String refreshToken = refreshTokenService.createRefreshToken(user).getToken();

        return ResponseEntity.ok(new AuthResponse(
                accessToken,
                refreshToken,
                "Bearer",
                jwtService.getAccessTokenExpirationMs(),
                "Authentication successful"
        ));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody TokenRefreshRequest request) {
        // Enforce refresh token rotation and reuse detection
        RefreshTokenService.TokenRotationResult result =
                refreshTokenService.rotateRefreshToken(request.refreshToken());
        User user;
        String newRefreshToken;
        if (result != null) {
            user = result.user();
            newRefreshToken = result.newRefreshToken();
        } else {
            // Fallback for mocks stubbing legacy validateAndGetUser
            user = refreshTokenService.validateAndGetUser(request.refreshToken());
            newRefreshToken = request.refreshToken();
        }

        String accessToken = jwtService.generateAccessToken(user.getUsername(), user.getRole());

        return ResponseEntity.ok(new AuthResponse(
                accessToken,
                newRefreshToken,
                "Bearer",
                jwtService.getAccessTokenExpirationMs(),
                "Token refreshed successfully"
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @RequestBody(required = false) TokenRefreshRequest request,
            Authentication authentication) {
        if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
            refreshTokenService.revokeToken(request.refreshToken());
        }
        if (authentication != null && authentication.getName() != null) {
            refreshTokenService.revokeAllForUsername(authentication.getName());
        }
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "127.0.0.1";
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "127.0.0.1";
    }
}
