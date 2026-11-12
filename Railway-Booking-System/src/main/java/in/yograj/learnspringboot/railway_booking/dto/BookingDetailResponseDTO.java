package in.yograj.learnspringboot.railway_booking.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public record BookingDetailResponseDTO(
        Long bookingId,
        String bookingRef,
        String pnr,
        Long userId,
        String username,
        Long journeyId,
        String trainNumber,
        String trainName,
        String fromStationCode,
        String fromStationName,
        String toStationCode,
        String toStationName,
        LocalDate journeyDate,
        LocalTime departureTime,
        LocalTime arrivalTime,
        Long coachId,
        String coachNumber,
        String coachType,
        Long seatId,
        Integer seatNumber,
        String seatType,
        Long seatAllocationId,
        String bookingStatus,
        String paymentStatus,
        BigDecimal amount,
        LocalDateTime createdAt,
        List<PassengerResponseDTO> passengers,
        FareComponentDTO fareBreakdown,
        Integer distanceKm
) {
    /**
     * Backward-compatible 25-parameter constructor (Phase 17.16 without PNR).
     */
    public BookingDetailResponseDTO(
            Long bookingId,
            String bookingRef,
            Long userId,
            String username,
            Long journeyId,
            String trainNumber,
            String trainName,
            String fromStationCode,
            String fromStationName,
            String toStationCode,
            String toStationName,
            LocalDate journeyDate,
            LocalTime departureTime,
            LocalTime arrivalTime,
            Long coachId,
            String coachNumber,
            String coachType,
            Long seatId,
            Integer seatNumber,
            String seatType,
            Long seatAllocationId,
            String bookingStatus,
            String paymentStatus,
            BigDecimal amount,
            LocalDateTime createdAt
    ) {
        this(
                bookingId, bookingRef, null, userId, username, journeyId, trainNumber, trainName,
                fromStationCode, fromStationName, toStationCode, toStationName, journeyDate,
                departureTime, arrivalTime, coachId, coachNumber, coachType, seatId,
                seatNumber, seatType, seatAllocationId, bookingStatus, paymentStatus,
                amount, createdAt, List.of(), null, null
        );
    }

    /**
     * Backward-compatible 26-parameter constructor with PNR (Canonical baseline).
     */
    public BookingDetailResponseDTO(
            Long bookingId,
            String bookingRef,
            String pnr,
            Long userId,
            String username,
            Long journeyId,
            String trainNumber,
            String trainName,
            String fromStationCode,
            String fromStationName,
            String toStationCode,
            String toStationName,
            LocalDate journeyDate,
            LocalTime departureTime,
            LocalTime arrivalTime,
            Long coachId,
            String coachNumber,
            String coachType,
            Long seatId,
            Integer seatNumber,
            String seatType,
            Long seatAllocationId,
            String bookingStatus,
            String paymentStatus,
            BigDecimal amount,
            LocalDateTime createdAt
    ) {
        this(
                bookingId, bookingRef, pnr, userId, username, journeyId, trainNumber, trainName,
                fromStationCode, fromStationName, toStationCode, toStationName, journeyDate,
                departureTime, arrivalTime, coachId, coachNumber, coachType, seatId,
                seatNumber, seatType, seatAllocationId, bookingStatus, paymentStatus,
                amount, createdAt, List.of(), null, null
        );
    }

    /**
     * Backward-compatible 27-parameter constructor with passengers (Phase 17.18).
     */
    public BookingDetailResponseDTO(
            Long bookingId,
            String bookingRef,
            Long userId,
            String username,
            Long journeyId,
            String trainNumber,
            String trainName,
            String fromStationCode,
            String fromStationName,
            String toStationCode,
            String toStationName,
            LocalDate journeyDate,
            LocalTime departureTime,
            LocalTime arrivalTime,
            Long coachId,
            String coachNumber,
            String coachType,
            Long seatId,
            Integer seatNumber,
            String seatType,
            Long seatAllocationId,
            String bookingStatus,
            String paymentStatus,
            BigDecimal amount,
            LocalDateTime createdAt,
            List<PassengerResponseDTO> passengers
    ) {
        this(
                bookingId, bookingRef, null, userId, username, journeyId, trainNumber, trainName,
                fromStationCode, fromStationName, toStationCode, toStationName, journeyDate,
                departureTime, arrivalTime, coachId, coachNumber, coachType, seatId,
                seatNumber, seatType, seatAllocationId, bookingStatus, paymentStatus,
                amount, createdAt, passengers, null, null
        );
    }

    /**
     * Backward-compatible 28-parameter constructor with fare breakdown (Phase 17.19).
     */
    public BookingDetailResponseDTO(
            Long bookingId,
            String bookingRef,
            Long userId,
            String username,
            Long journeyId,
            String trainNumber,
            String trainName,
            String fromStationCode,
            String fromStationName,
            String toStationCode,
            String toStationName,
            LocalDate journeyDate,
            LocalTime departureTime,
            LocalTime arrivalTime,
            Long coachId,
            String coachNumber,
            String coachType,
            Long seatId,
            Integer seatNumber,
            String seatType,
            Long seatAllocationId,
            String bookingStatus,
            String paymentStatus,
            BigDecimal amount,
            LocalDateTime createdAt,
            List<PassengerResponseDTO> passengers,
            FareComponentDTO fareBreakdown,
            Integer distanceKm
    ) {
        this(
                bookingId, bookingRef, null, userId, username, journeyId, trainNumber, trainName,
                fromStationCode, fromStationName, toStationCode, toStationName, journeyDate,
                departureTime, arrivalTime, coachId, coachNumber, coachType, seatId,
                seatNumber, seatType, seatAllocationId, bookingStatus, paymentStatus,
                amount, createdAt, passengers, fareBreakdown, distanceKm
        );
    }
}
