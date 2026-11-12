package in.yograj.learnspringboot.railway_booking.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record BookingResponseDTO(
        Long bookingId,
        String bookingRef,
        String pnr,
        Long journeyId,
        Long seatId,
        String coachNumber,
        Integer seatNumber,
        String bookingStatus,
        String paymentStatus,
        BigDecimal amount,
        LocalDateTime createdAt,
        List<PassengerResponseDTO> passengers,
        FareComponentDTO fareBreakdown,
        Integer distanceKm
) {
    /**
     * Backward-compatible 10-parameter constructor (Phase 17.16).
     */
    public BookingResponseDTO(
            Long bookingId,
            String bookingRef,
            Long journeyId,
            Long seatId,
            String coachNumber,
            Integer seatNumber,
            String bookingStatus,
            String paymentStatus,
            BigDecimal amount,
            LocalDateTime createdAt
    ) {
        this(bookingId, bookingRef, null, journeyId, seatId, coachNumber, seatNumber,
                bookingStatus, paymentStatus, amount, createdAt, List.of(), null, null);
    }

    /**
     * Backward-compatible 11-parameter constructor with PNR (Canonical baseline).
     */
    public BookingResponseDTO(
            Long bookingId,
            String bookingRef,
            String pnr,
            Long journeyId,
            Long seatId,
            String coachNumber,
            Integer seatNumber,
            String bookingStatus,
            String paymentStatus,
            BigDecimal amount,
            LocalDateTime createdAt
    ) {
        this(bookingId, bookingRef, pnr, journeyId, seatId, coachNumber, seatNumber,
                bookingStatus, paymentStatus, amount, createdAt, List.of(), null, null);
    }

    /**
     * Backward-compatible 12-parameter constructor with passengers (Phase 17.18).
     */
    public BookingResponseDTO(
            Long bookingId,
            String bookingRef,
            Long journeyId,
            Long seatId,
            String coachNumber,
            Integer seatNumber,
            String bookingStatus,
            String paymentStatus,
            BigDecimal amount,
            LocalDateTime createdAt,
            List<PassengerResponseDTO> passengers
    ) {
        this(bookingId, bookingRef, null, journeyId, seatId, coachNumber, seatNumber,
                bookingStatus, paymentStatus, amount, createdAt, passengers, null, null);
    }

    /**
     * Backward-compatible 13-parameter constructor with fare breakdown (Phase 17.19).
     */
    public BookingResponseDTO(
            Long bookingId,
            String bookingRef,
            Long journeyId,
            Long seatId,
            String coachNumber,
            Integer seatNumber,
            String bookingStatus,
            String paymentStatus,
            BigDecimal amount,
            LocalDateTime createdAt,
            List<PassengerResponseDTO> passengers,
            FareComponentDTO fareBreakdown,
            Integer distanceKm
    ) {
        this(bookingId, bookingRef, null, journeyId, seatId, coachNumber, seatNumber,
                bookingStatus, paymentStatus, amount, createdAt, passengers, fareBreakdown, distanceKm);
    }
}
