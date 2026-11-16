package in.yograj.learnspringboot.railway_booking.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Public PNR Enquiry Response DTO.
 * Explicitly excludes user IDs, account usernames, passwords, phone numbers,
 * email addresses, and payment secrets.
 * Masks passenger names for privacy compliance.
 */
public record PublicPnrResponseDTO(
        String pnr,
        String bookingRef,
        String trainNumber,
        String trainName,
        String fromStationCode,
        String fromStationName,
        String toStationCode,
        String toStationName,
        LocalDate journeyDate,
        LocalTime departureTime,
        LocalTime arrivalTime,
        String coachNumber,
        String coachType,
        Integer seatNumber,
        String seatType,
        String bookingStatus,
        String paymentStatus,
        BigDecimal amount,
        Integer distanceKm,
        List<PublicPassengerDTO> passengers
) {
    public record PublicPassengerDTO(
            int passengerNumber,
            String maskedName,
            String gender,
            String coachNumber,
            Integer seatNumber,
            String seatType,
            String bookingStatus
    ) {}
}
