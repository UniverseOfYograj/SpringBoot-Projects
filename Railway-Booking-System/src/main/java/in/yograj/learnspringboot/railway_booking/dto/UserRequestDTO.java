package in.yograj.learnspringboot.railway_booking.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record UserRequestDTO(
        @NotBlank(message = "Username is required") String username,
        @NotBlank(message = "Full name is required") String fullName,
        @NotBlank(message = "Email is required") @Email(message = "Email should be valid") String email,
        String phoneNumber,
        @NotBlank(message = "Password is required") String password
) {}