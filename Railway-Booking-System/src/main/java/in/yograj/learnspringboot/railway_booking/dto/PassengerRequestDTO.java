package in.yograj.learnspringboot.railway_booking.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PassengerRequestDTO(
        @NotBlank(message = "Passenger name is required")
        @Size(min = 2, max = 100, message = "Passenger name must be between 2 and 100 characters")
        String fullName,

        @NotNull(message = "Passenger age is required")
        @Min(value = 1, message = "Passenger age must be at least 1")
        @Max(value = 125, message = "Passenger age must not exceed 125")
        Integer age,

        @NotBlank(message = "Passenger gender is required")
        String gender,

        @NotNull(message = "Seat ID is required")
        Long seatId,

        String berthPreference
) {
}
