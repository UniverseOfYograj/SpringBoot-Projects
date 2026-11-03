package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.BookingDetailResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.BookingResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.CreateBookingRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PageResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.PaymentResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.RazorpayOrderResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.RazorpayPaymentStatusDTO;
import in.yograj.learnspringboot.railway_booking.dto.RazorpayVerifyRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.SimulatedPaymentRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.SimulatedPaymentSummaryDTO;
import in.yograj.learnspringboot.railway_booking.service.BookingService;
import in.yograj.learnspringboot.railway_booking.service.RazorpayPaymentService;
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
    private final RazorpayPaymentService razorpayPaymentService;

    public BookingController(BookingService bookingService, RazorpayPaymentService razorpayPaymentService) {
        this.bookingService = bookingService;
        this.razorpayPaymentService = razorpayPaymentService;
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
    @GetMapping("/{bookingRef}/simulated")
    public ResponseEntity<SimulatedPaymentSummaryDTO> getSimulatedPaymentSummary(
            @PathVariable String bookingRef,
            Authentication authentication) {
        return ResponseEntity.ok(bookingService.getSimulatedPaymentSummary(
                bookingRef, authentication.getName()));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{bookingRef}/simulated/initiate")
    public ResponseEntity<SimulatedPaymentSummaryDTO> initiateSimulatedPayment(
            @PathVariable String bookingRef,
            Authentication authentication) {
        return ResponseEntity.ok(bookingService.initiateSimulatedPayment(
                bookingRef, authentication.getName()));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{bookingRef}/simulated/success")
    public ResponseEntity<PaymentResponseDTO> simulatePaymentSuccess(
            @PathVariable String bookingRef,
            @RequestBody(required = false) SimulatedPaymentRequestDTO request,
            Authentication authentication) {
        String idempotencyKey = request != null ? request.idempotencyKey() : null;
        return ResponseEntity.ok(bookingService.processSimulatedPaymentSuccess(
                bookingRef, idempotencyKey, authentication.getName()));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping(value = {"/{bookingRef}/simulated/failure", "/{bookingRef}/simulated/fail"})
    public ResponseEntity<PaymentResponseDTO> simulatePaymentFailure(
            @PathVariable String bookingRef,
            @RequestBody(required = false) SimulatedPaymentRequestDTO request,
            Authentication authentication) {
        String idempotencyKey = request != null ? request.idempotencyKey() : null;
        String reason = request != null ? request.reason() : null;
        return ResponseEntity.ok(bookingService.processSimulatedPaymentFailure(
                bookingRef, idempotencyKey, reason, authentication.getName()));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{bookingRef}/razorpay/order")
    public ResponseEntity<RazorpayOrderResponseDTO> createRazorpayOrder(
            @PathVariable String bookingRef,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(razorpayPaymentService.createOrder(bookingRef, authentication.getName()));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{bookingRef}/razorpay/verify")
    public ResponseEntity<RazorpayPaymentStatusDTO> verifyRazorpayPayment(
            @PathVariable String bookingRef,
            @Valid @RequestBody RazorpayVerifyRequestDTO request,
            Authentication authentication) {
        return ResponseEntity.ok(razorpayPaymentService.verifyCheckout(
                bookingRef, authentication.getName(), request));
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
    @GetMapping("/pnr/{pnr}")
    public ResponseEntity<BookingDetailResponseDTO> getBookingByPnr(
            @PathVariable String pnr,
            Authentication authentication) {
        return ResponseEntity.ok(bookingService.getBookingByPnr(pnr, authentication.getName()));
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
