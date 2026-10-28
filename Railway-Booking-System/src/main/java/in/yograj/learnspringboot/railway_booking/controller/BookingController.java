package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.BookingDetailResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.BookingResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.CreateBookingRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PageResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentResponseDTO;
import in.yograj.learnspringboot.railway_booking.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<BookingResponseDTO> createBooking(
            @Valid @RequestBody CreateBookingRequestDTO request,
            Authentication authentication,
            @RequestHeader(name = "X-Tatkal-Admission", required = false) String admissionPass) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bookingService.createBooking(request, authentication.getName(), admissionPass));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{bookingRef}/pay")
    public ResponseEntity<PaymentResponseDTO> payBooking(
            @PathVariable String bookingRef,
            @RequestBody(required = false) PaymentRequestDTO request,
            Authentication authentication) {
        return ResponseEntity.ok(bookingService.processPayment(
                bookingRef, request, authentication.getName()));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{bookingRef}/cancel")
    public ResponseEntity<BookingDetailResponseDTO> cancelBooking(
            @PathVariable String bookingRef,
            Authentication authentication) {
        return ResponseEntity.ok(bookingService.cancelBooking(bookingRef, authentication.getName()));
    }

    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/{bookingRef}")
    public ResponseEntity<BookingDetailResponseDTO> cancelBookingDelete(
            @PathVariable String bookingRef,
            Authentication authentication) {
        return ResponseEntity.ok(bookingService.cancelBooking(bookingRef, authentication.getName()));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{bookingRef}")
    public ResponseEntity<BookingDetailResponseDTO> getBookingByRef(
            @PathVariable String bookingRef,
            Authentication authentication) {
        return ResponseEntity.ok(bookingService.getBookingByRef(bookingRef, authentication.getName()));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<PageResponseDTO<BookingDetailResponseDTO>> getBookingHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication authentication) {
        return ResponseEntity.ok(bookingService.getBookingHistory(page, size, authentication.getName()));
    }
}
