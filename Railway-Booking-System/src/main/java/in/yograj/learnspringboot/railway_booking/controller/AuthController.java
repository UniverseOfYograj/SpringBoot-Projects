package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.AuthResponse;
import in.yograj.learnspringboot.railway_booking.dto.LoginRequest;
import in.yograj.learnspringboot.railway_booking.dto.TokenRefreshRequest;
import in.yograj.learnspringboot.railway_booking.dto.UserRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.UserResponseDTO;
import in.yograj.learnspringboot.railway_booking.entity.User;
import in.yograj.learnspringboot.railway_booking.repository.UserRepository;
import in.yograj.learnspringboot.railway_booking.security.JwtService;
import in.yograj.learnspringboot.railway_booking.service.RefreshTokenService;
import in.yograj.learnspringboot.railway_booking.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // AuthController user register, login aur refresh flow ko handle karta hai.
    // Real booking platform me login request ke baad access token aur refresh token dono milte hain.

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;

    public AuthController(AuthenticationManager authenticationManager,
                         JwtService jwtService,
                         UserService userService,
                         UserRepository userRepository,
                         RefreshTokenService refreshTokenService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponseDTO> register(@Valid @RequestBody UserRequestDTO request) {
        System.out.println("AUTH CONTROLLER REGISTER HIT: " + request.username());
        UserResponseDTO response = userService.registerUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );

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
        String refreshToken = request.refreshToken();
        User user = refreshTokenService.validateAndGetUser(refreshToken);

        String accessToken = jwtService.generateAccessToken(user.getUsername(), user.getRole());

        return ResponseEntity.ok(new AuthResponse(
                accessToken,
                refreshToken,
                "Bearer",
                jwtService.getAccessTokenExpirationMs(),
                "Token refreshed successfully"
        ));
    }
}
