package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.config.RazorpayProperties;
import in.yograj.learnspringboot.railway_booking.dto.RazorpayOrderResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.RazorpayPaymentStatusDTO;
import in.yograj.learnspringboot.railway_booking.dto.RazorpayVerifyRequestDTO;
import in.yograj.learnspringboot.railway_booking.entity.Booking;
import in.yograj.learnspringboot.railway_booking.entity.PaymentTransaction;
import in.yograj.learnspringboot.railway_booking.entity.RazorpayRefund;
import in.yograj.learnspringboot.railway_booking.entity.RazorpayWebhookEvent;
import in.yograj.learnspringboot.railway_booking.entity.SeatAllocation;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.outbox.OutboxEventService;
import in.yograj.learnspringboot.railway_booking.outbox.event.BookingConfirmedEvent;
import in.yograj.learnspringboot.railway_booking.outbox.event.PaymentFailedEvent;
import in.yograj.learnspringboot.railway_booking.repository.BookingRepository;
import in.yograj.learnspringboot.railway_booking.repository.PaymentTransactionRepository;
import in.yograj.learnspringboot.railway_booking.repository.RazorpayRefundRepository;
import in.yograj.learnspringboot.railway_booking.repository.RazorpayWebhookEventRepository;
import in.yograj.learnspringboot.railway_booking.repository.SeatAllocationRepository;
import in.yograj.learnspringboot.railway_booking.observability.RailoraMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class RazorpayPaymentService {

    private static final String PAYMENT_METHOD = "RAZORPAY";
    private static final String PENDING = "PENDING";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";
    private static final String CONFIRMED = "CONFIRMED";
    private static final String PENDING_PAYMENT = "PENDING_PAYMENT";
    private static final String HOLD = "HOLD";
    private static final String BOOKED = "BOOKED";
    private static final String REFUND_REQUIRED = "REFUND_REQUIRED";

    private final RazorpayProperties properties;
    private final RazorpayGatewayClient gatewayClient;
    private final BookingRepository bookingRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final SeatAllocationRepository allocationRepository;
    private final RazorpayWebhookEventRepository webhookEventRepository;
    private final RazorpayRefundRepository refundRepository;
    private final OutboxEventService outboxEventService;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private RailoraMetrics railoraMetrics;

    public RazorpayPaymentService(
            RazorpayProperties properties,
            RazorpayGatewayClient gatewayClient,
            BookingRepository bookingRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            SeatAllocationRepository allocationRepository,
            RazorpayWebhookEventRepository webhookEventRepository,
            RazorpayRefundRepository refundRepository,
            OutboxEventService outboxEventService,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.gatewayClient = gatewayClient;
        this.bookingRepository = bookingRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.allocationRepository = allocationRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.refundRepository = refundRepository;
        this.outboxEventService = outboxEventService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RazorpayOrderResponseDTO createOrder(String bookingRef, String username) {
        requireEnabled();
        Booking booking = bookingRepository.findByBookingRefForUpdate(bookingRef)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        requireOwner(booking, username);
        requirePendingBooking(booking);

        BigDecimal amount = validBookingAmount(booking);
        SeatAllocation allocation = requireActiveHold(booking);
        LocalDateTime now = LocalDateTime.now();
        if (allocation.getHoldExpiresAt() != null && !allocation.getHoldExpiresAt().isAfter(now)) {
            throw new IllegalStateException("Seat hold has expired; create a new booking before paying");
        }

        Optional<PaymentTransaction> existingOrder = paymentTransactionRepository
                .findFirstByBookingRefAndPaymentMethodAndStatusOrderByCreatedAtDesc(
                        bookingRef, PAYMENT_METHOD, PENDING);
        if (existingOrder.isPresent() && existingOrder.get().getGatewayOrderId() != null) {
            return orderResponse(existingOrder.get());
        }

        long amountPaise = amount.movePointRight(2).longValueExact();
        String receipt = "RR-" + UUID.randomUUID();
        RazorpayGatewayClient.GatewayOrder gatewayOrder = gatewayClient.createOrder(amountPaise, receipt);
        if (gatewayOrder == null || gatewayOrder.id() == null || gatewayOrder.id().isBlank()
                || gatewayOrder.amount() != amountPaise || !"INR".equals(gatewayOrder.currency())) {
            throw new IllegalStateException("Razorpay returned an invalid order response");
        }

        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setBooking(booking);
        transaction.setBookingRef(booking.getBookingRef());
        transaction.setTransactionRef("RP-" + UUID.randomUUID().toString().replace("-", "").toUpperCase());
        transaction.setPaymentMethod(PAYMENT_METHOD);
        transaction.setAmount(amount);
        transaction.setStatus(PENDING);
        transaction.setSimulateFailure(false);
        transaction.setGatewayOrderId(gatewayOrder.id());
        transaction.setGatewayOrderReceipt(receipt);
        transaction.setCreatedAt(now);
        return orderResponse(paymentTransactionRepository.save(transaction));
    }

    @Transactional
    public RazorpayPaymentStatusDTO verifyCheckout(String bookingRef, String username, RazorpayVerifyRequestDTO request) {
        requireEnabled();
        if (request == null || blank(request.orderId()) || blank(request.paymentId()) || blank(request.signature())) {
            throw new BadRequestException("Order ID, payment ID, and signature are required");
        }

        Booking booking = bookingRepository.findByBookingRefForUpdate(bookingRef)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        requireOwner(booking, username);
        PaymentTransaction transaction = paymentTransactionRepository.findByGatewayOrderIdForUpdate(request.orderId())
                .orElseThrow(() -> new BadRequestException("Payment order does not match this booking"));
        if (!transaction.getBookingRef().equals(bookingRef)) {
            throw new BadRequestException("Payment order does not match this booking");
        }
        verifyCheckoutSignature(request.orderId(), request.paymentId(), request.signature());

        RazorpayGatewayClient.GatewayPayment payment = gatewayClient.fetchPayment(request.paymentId());
        validateGatewayPayment(payment, request.orderId(), request.paymentId(), paise(transaction.getAmount()));
        if (!"captured".equalsIgnoreCase(payment.status())) {
            return paymentStatus(transaction, booking, "Payment is verified and awaiting capture confirmation");
        }

        applyCapturedPayment(transaction, booking, payment.id());
        return paymentStatus(transaction, booking, statusMessage(booking));
    }

    @Transactional
    public void processWebhook(byte[] rawBody, String signature, String eventId) {
        requireEnabled();
        if (rawBody == null || rawBody.length == 0 || blank(eventId)) {
            throw new BadRequestException("Webhook body and event ID are required");
        }
        verifyHmac(rawBody, signature, properties.getWebhookSecret(), "Invalid Razorpay webhook signature");
        if (webhookEventRepository.existsById(eventId)) {
            return;
        }

        final JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (JacksonException exception) {
            throw new BadRequestException("Invalid Razorpay webhook payload");
        }
        String eventType = text(payload, "event");
        String entityId = null;
        if ("payment.captured".equals(eventType) || "payment.failed".equals(eventType)) {
            JsonNode paymentNode = entityNode(payload, "payment");
            entityId = text(paymentNode, "id");
            processPaymentWebhook(eventType, paymentNode);
        } else if ("refund.processed".equals(eventType) || "refund.failed".equals(eventType)) {
            JsonNode refundNode = entityNode(payload, "refund");
            entityId = text(refundNode, "id");
            processRefundWebhook(eventType, refundNode);
        }

        webhookEventRepository.save(new RazorpayWebhookEvent(
                eventId, eventType == null ? "unknown" : eventType, entityId, LocalDateTime.now()));
    }

    public void verifyCheckoutSignature(String orderId, String paymentId, String signature) {
        verifyHmac((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8),
                signature, properties.getKeySecret(), "Invalid Razorpay checkout signature");
    }

    private void processPaymentWebhook(String eventType, JsonNode paymentNode) {
        String orderId = text(paymentNode, "order_id");
        String paymentId = text(paymentNode, "id");
        if (blank(paymentId)) {
            throw new BadRequestException("Webhook payment ID is missing");
        }
        PaymentTransaction transaction = paymentTransactionRepository.findByGatewayOrderIdForUpdate(orderId)
                .orElseThrow(() -> new BadRequestException("Webhook references an unknown payment order"));
        Booking booking = bookingRepository.findByBookingRefForUpdate(transaction.getBookingRef())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        long amount = number(paymentNode, "amount");
        if (amount != paise(transaction.getAmount()) || !"INR".equals(text(paymentNode, "currency"))) {
            throw new BadRequestException("Webhook payment amount or currency does not match the order");
        }
        if ("payment.captured".equals(eventType)) {
            if (!"captured".equalsIgnoreCase(text(paymentNode, "status"))) {
                throw new BadRequestException("Captured payment webhook has an invalid payment status");
            }
            applyCapturedPayment(transaction, booking, paymentId);
        } else if (!SUCCESS.equalsIgnoreCase(transaction.getStatus())) {
            if (!"failed".equalsIgnoreCase(text(paymentNode, "status"))) {
                throw new BadRequestException("Failed payment webhook has an invalid payment status");
            }
            transaction.setStatus(FAILED);
            transaction.setFailureReason("Payment failed at Razorpay");
            if (railoraMetrics != null) {
                railoraMetrics.recordPaymentAttempt(PAYMENT_METHOD, "failed", "declined");
            }
            paymentTransactionRepository.save(transaction);
            if (PENDING_PAYMENT.equalsIgnoreCase(booking.getBookingStatus())) {
                booking.setPaymentStatus(FAILED);
                bookingRepository.save(booking);
                outboxEventService.stageEvent(
                        "PAYMENT",
                        transaction.getTransactionRef(),
                        "PAYMENT_FAILED",
                        new PaymentFailedEvent(
                                booking.getBookingRef(),
                                transaction.getTransactionRef(),
                                transaction.getAmount(),
                                PAYMENT_METHOD,
                                transaction.getFailureReason(),
                                LocalDateTime.now()));
            }
        }
    }

    private void processRefundWebhook(String eventType, JsonNode refundNode) {
        String refundId = text(refundNode, "id");
        String paymentId = text(refundNode, "payment_id");
        if (blank(refundId) || blank(paymentId)) {
            throw new BadRequestException("Webhook refund reference is incomplete");
        }
        PaymentTransaction transaction = paymentTransactionRepository.findByGatewayPaymentId(paymentId)
                .orElseThrow(() -> new BadRequestException("Webhook references an unknown payment"));
        long amountPaise = number(refundNode, "amount");
        if (amountPaise <= 0 || amountPaise > paise(transaction.getAmount())) {
            throw new BadRequestException("Webhook refund amount is invalid");
        }
        String currency = text(refundNode, "currency");
        if (currency != null && !"INR".equals(currency)) {
            throw new BadRequestException("Webhook refund currency does not match the payment");
        }
        RazorpayRefund refund = refundRepository.findById(refundId).orElseGet(() -> {
            RazorpayRefund newRefund = new RazorpayRefund();
            newRefund.setGatewayRefundId(refundId);
            newRefund.setPaymentTransaction(transaction);
            newRefund.setAmount(BigDecimal.valueOf(amountPaise, 2));
            newRefund.setCreatedAt(LocalDateTime.now());
            return newRefund;
        });
        if (refund.getAmount().compareTo(BigDecimal.valueOf(amountPaise, 2)) != 0) {
            throw new BadRequestException("Webhook refund amount does not match the stored refund");
        }
        if ("PROCESSED".equals(refund.getStatus())) {
            return;
        }
        refund.setStatus("refund.processed".equals(eventType) ? "PROCESSED" : "FAILED");
        refund.setUpdatedAt(LocalDateTime.now());
        refundRepository.save(refund);
        if ("PROCESSED".equals(refund.getStatus())) {
            BigDecimal processedTotal = refundRepository.sumAmountByPaymentTransactionIdAndStatus(
                    transaction.getPaymentId(), "PROCESSED");
            Booking booking = bookingRepository.findByBookingRefForUpdate(transaction.getBookingRef())
                    .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
            if (processedTotal.compareTo(transaction.getAmount()) >= 0) {
                booking.setPaymentStatus("REFUNDED");
            } else {
                booking.setPaymentStatus("PARTIALLY_REFUNDED");
            }
            bookingRepository.save(booking);
            outboxEventService.stageEvent(
                    "PAYMENT",
                    transaction.getTransactionRef(),
                    "PAYMENT_REFUND_PROCESSED",
                    new RefundProcessedEvent(booking.getBookingRef(), refund.getGatewayRefundId(), refund.getAmount(), booking.getPaymentStatus(), refund.getUpdatedAt()));
        }
    }

    private void applyCapturedPayment(PaymentTransaction transaction, Booking booking, String paymentId) {
        if (SUCCESS.equalsIgnoreCase(transaction.getStatus())
                && paymentId.equals(transaction.getGatewayPaymentId())) {
            if (railoraMetrics != null) {
                railoraMetrics.recordPaymentAttempt(PAYMENT_METHOD, "idempotent_replay", "none");
            }
            return;
        }
        Optional<PaymentTransaction> duplicatePayment =
                paymentTransactionRepository.findByGatewayPaymentId(paymentId);
        if (duplicatePayment.isPresent() && !duplicatePayment.get().getPaymentId().equals(transaction.getPaymentId())) {
            throw new BadRequestException("Payment ID has already been applied to another transaction");
        }

        transaction.setGatewayPaymentId(paymentId);
        transaction.setStatus(SUCCESS);
        transaction.setFailureReason(null);
        paymentTransactionRepository.save(transaction);

        LocalDateTime now = LocalDateTime.now();
        SeatAllocation allocation = booking.getSeatAllocation() == null
                ? null
                : allocationRepository.findById(booking.getSeatAllocation().getSeatAllocationId()).orElse(null);
        boolean canConfirm = PENDING_PAYMENT.equalsIgnoreCase(booking.getBookingStatus())
                && allocation != null
                && HOLD.equalsIgnoreCase(allocation.getStatus())
                && (allocation.getHoldExpiresAt() == null || allocation.getHoldExpiresAt().isAfter(now));
        if (!canConfirm) {
            booking.setPaymentStatus(REFUND_REQUIRED);
            if (railoraMetrics != null) {
                railoraMetrics.recordPaymentAttempt(PAYMENT_METHOD, "failure", "expired_hold");
            }
            bookingRepository.save(booking);
            outboxEventService.stageEvent(
                    "PAYMENT",
                    transaction.getTransactionRef(),
                    "PAYMENT_CAPTURED_REQUIRES_REFUND_REVIEW",
                    new CapturedPaymentReviewEvent(booking.getBookingRef(), transaction.getTransactionRef(), paymentId, transaction.getAmount(), now));
            return;
        }

        booking.setBookingStatus(CONFIRMED);
        booking.setPaymentStatus(SUCCESS);
        if (railoraMetrics != null) {
            railoraMetrics.recordPaymentAttempt(PAYMENT_METHOD, "success", "none");
        }
        bookingRepository.save(booking);
        allocation.setStatus(BOOKED);
        allocation.setUpdatedAt(now);
        allocationRepository.save(allocation);
        outboxEventService.stageEvent(
                "BOOKING",
                booking.getBookingRef(),
                "BOOKING_CONFIRMED",
                new BookingConfirmedEvent(
                        booking.getBookingRef(),
                        booking.getPnr() == null ? "" : booking.getPnr(),
                        booking.getBookingId(),
                        booking.getUser().getUserId(),
                        booking.getUser().getUsername(),
                        booking.getJourney().getJourneyId(),
                        transaction.getAmount(),
                        PAYMENT_METHOD,
                        transaction.getTransactionRef(),
                        CONFIRMED,
                        now));
    }

    private void validateGatewayPayment(
            RazorpayGatewayClient.GatewayPayment payment,
            String expectedOrderId,
            String expectedPaymentId,
            long expectedAmount) {
        if (payment == null || !expectedPaymentId.equals(payment.id())
                || !expectedOrderId.equals(payment.orderId())
                || payment.amount() != expectedAmount || !"INR".equals(payment.currency())) {
            throw new BadRequestException("Razorpay payment does not match the server order");
        }
    }

    private RazorpayOrderResponseDTO orderResponse(PaymentTransaction transaction) {
        return new RazorpayOrderResponseDTO(
                transaction.getGatewayOrderId(),
                properties.getKeyId(),
                transaction.getBookingRef(),
                paise(transaction.getAmount()),
                "INR",
                transaction.getTransactionRef());
    }

    private RazorpayPaymentStatusDTO paymentStatus(
            PaymentTransaction transaction, Booking booking, String message) {
        return new RazorpayPaymentStatusDTO(
                transaction.getTransactionRef(),
                booking.getBookingRef(),
                transaction.getStatus(),
                booking.getBookingStatus(),
                message);
    }

    private String statusMessage(Booking booking) {
        return REFUND_REQUIRED.equalsIgnoreCase(booking.getPaymentStatus())
                ? "Payment was captured after the booking became ineligible; manual refund review is required"
                : "Payment verified and booking confirmed";
    }

    private SeatAllocation requireActiveHold(Booking booking) {
        if (booking.getSeatAllocation() == null) {
            throw new IllegalStateException("Booking does not have an associated seat allocation");
        }
        SeatAllocation allocation = allocationRepository.findById(
                        booking.getSeatAllocation().getSeatAllocationId())
                .orElseThrow(() -> new IllegalStateException("Associated seat allocation not found"));
        if (!HOLD.equalsIgnoreCase(allocation.getStatus())) {
            throw new IllegalStateException("Seat allocation is no longer available for payment");
        }
        return allocation;
    }

    private BigDecimal validBookingAmount(Booking booking) {
        if (booking.getAmount() == null || booking.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Booking has no valid persisted fare; Razorpay payment is unavailable");
        }
        try {
            return booking.getAmount().setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("Booking fare must use INR precision of two decimal places");
        }
    }

    private long paise(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Razorpay payments are disabled");
        }
    }

    private void requireOwner(Booking booking, String username) {
        if (username == null || booking.getUser() == null
                || !booking.getUser().getUsername().equals(username)) {
            throw new AccessDeniedException("You are not authorized to pay for this booking");
        }
    }

    private void requirePendingBooking(Booking booking) {
        if (!PENDING_PAYMENT.equalsIgnoreCase(booking.getBookingStatus())) {
            throw new IllegalStateException("Booking is not eligible for payment");
        }
    }

    private void verifyHmac(byte[] body, String suppliedHex, String secret, String errorMessage) {
        if (blank(suppliedHex) || blank(secret)) {
            throw new BadRequestException(errorMessage);
        }
        try {
            byte[] supplied = HexFormat.of().parseHex(suppliedHex);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (!MessageDigest.isEqual(mac.doFinal(body), supplied)) {
                throw new BadRequestException(errorMessage);
            }
        } catch (IllegalArgumentException | java.security.GeneralSecurityException exception) {
            throw new BadRequestException(errorMessage);
        }
    }

    private JsonNode entityNode(JsonNode payload, String name) {
        return payload.path("payload").path(name).path("entity");
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private long number(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isNumber()) {
            throw new BadRequestException("Webhook amount is invalid");
        }
        return value.asLong();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record CapturedPaymentReviewEvent(
            String bookingRef, String transactionRef, String gatewayPaymentId, BigDecimal amount, LocalDateTime capturedAt) {
    }

    private record RefundProcessedEvent(
            String bookingRef, String gatewayRefundId, BigDecimal amount, String paymentStatus, LocalDateTime processedAt) {
    }
}
