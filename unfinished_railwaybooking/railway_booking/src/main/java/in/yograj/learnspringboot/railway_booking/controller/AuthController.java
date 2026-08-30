package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.LoginRequestDTO;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import in.yograj.learnspringboot.railway_booking.security.JwtService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import in.yograj.learnspringboot.railway_booking.security.JwtService;
import in.yograj.learnspringboot.railway_booking.security.CustomUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Map;
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;
    public AuthController(AuthenticationManager authenticationManager,
                          JwtService jwtService,
                          CustomUserDetailsService userDetailsService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }
    @PostMapping("/login")
    public Map<String, String> login(@Valid @RequestBody LoginRequestDTO dto) {

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        dto.email(),
                        dto.password()
                )
        );

        UserDetails userDetails = userDetailsService.loadUserByUsername(dto.email());

        String token = jwtService.generateToken(userDetails);

        return Map.of(
                "message", "Login successful",
                "token", token
        );
    }

}