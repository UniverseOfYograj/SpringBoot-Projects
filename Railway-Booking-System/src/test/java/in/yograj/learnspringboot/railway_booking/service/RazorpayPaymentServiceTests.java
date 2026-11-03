package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.config.RazorpayProperties;
import in.yograj.learnspringboot.railway_booking.dto.RazorpayVerifyRequestDTO;
import in.yograj.learnspringboot.railway_booking.entity.Booking;
import in.yograj.learnspringboot.railway_booking.entity.PaymentTransaction;
import in.yograj.learnspringboot.railway_booking.entity.RazorpayRefund;
import in.yograj.learnspringboot.railway_booking.entity.RazorpayWebhookEvent;
import in.yograj.learnspringboot.railway_booking.entity.SeatAllocation;
import in.yograj.learnspringboot.railway_booking.entity.Journey;
import in.yograj.learnspringboot.railway_booking.entity.User;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.outbox.OutboxEventService;
import in.yograj.learnspringboot.railway_booking.repository.BookingRepository;
import in.yograj.learnspringboot.railway_booking.repository.PaymentTransactionRepository;
import in.yograj.learnspringboot.railway_booking.repository.RazorpayRefundRepository;
import in.yograj.learnspringboot.railway_booking.repository.RazorpayWebhookEventRepository;
import in.yograj.learnspringboot.railway_booking.repository.SeatAllocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.access.AccessDeniedException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RazorpayPaymentServiceTests {

    @Mock
    private RazorpayGatewayClient gatewayClient;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private SeatAllocationRepository allocationRepository;
    @Mock
    private RazorpayWebhookEventRepository webhookEventRepository;
    @Mock
    private RazorpayRefundRepository refundRepository;
    @Mock
    private OutboxEventService outboxEventService;

    private RazorpayProperties properties;
    private RazorpayPaymentService service;

    @BeforeEach
    void setUp() {
        properties = new RazorpayProperties();
        properties.setEnabled(true);
        properties.setKeyId("rzp_test_public");
        properties.setKeySecret("api-secret");
        properties.setWebhookSecret("webhook-secret");
        service = new RazorpayPaymentService(
                properties,
                gatewayClient,
                bookingRepository,
                paymentTransactionRepository,
                allocationRepository,
                webhookEventRepository,
                refundRepository,
                outboxEventService,
                new ObjectMapper());
    }

    @Test
    void createOrderUsesPersistedFareAndConvertsToPaise() {
        Booking booking = booking("RB-ORDER");
        SeatAllocation allocation = activeHold();
        booking.setSeatAllocation(allocation);
        when(bookingRepository.findByBookingRefForUpdate("RB-ORDER")).thenReturn(Optional.of(booking));
        when(allocationRepository.findById(17L)).thenReturn(Optional.of(allocation));
        when(paymentTransactionRepository.findFirstByBookingRefAndPaymentMethodAndStatusOrderByCreatedAtDesc(
                "RB-ORDER", "RAZORPAY", "PENDING")).thenReturn(Optional.empty());
        when(gatewayClient.createOrder(eq(1234L), anyString()))
                .thenReturn(new RazorpayGatewayClient.GatewayOrder("order_1", 1234L, "INR", "receipt"));
        when(paymentTransactionRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.createOrder("RB-ORDER", "alice");

        assertEquals("order_1", result.orderId());
        assertEquals(1234L, result.amount());
        verify(gatewayClient).createOrder(eq(1234L), anyString());
    }

    @Test
    void razorpayOrderAmountMatchesPersistedBookingFare() {
        Booking booking = booking("RB-CONFIGURED-FARE");
        booking.setAmount(new BigDecimal("500.00"));
        SeatAllocation allocation = activeHold();
        booking.setSeatAllocation(allocation);
        when(bookingRepository.findByBookingRefForUpdate("RB-CONFIGURED-FARE"))
                .thenReturn(Optional.of(booking));
        when(allocationRepository.findById(17L)).thenReturn(Optional.of(allocation));
        when(paymentTransactionRepository.findFirstByBookingRefAndPaymentMethodAndStatusOrderByCreatedAtDesc(
                "RB-CONFIGURED-FARE", "RAZORPAY", "PENDING")).thenReturn(Optional.empty());
        when(gatewayClient.createOrder(eq(50000L), anyString()))
                .thenReturn(new RazorpayGatewayClient.GatewayOrder("order_500", 50000L, "INR", "receipt"));
        when(paymentTransactionRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.createOrder("RB-CONFIGURED-FARE", "alice");

        assertEquals(50000L, result.amount());
        verify(gatewayClient).createOrder(eq(50000L), anyString());
    }

    @Test
    void createOrderReusesExistingPendingGatewayOrder() {
        Booking booking = booking("RB-ORDER-REUSE");
        SeatAllocation allocation = activeHold();
        booking.setSeatAllocation(allocation);
        PaymentTransaction existing = transaction(booking, "order_existing");
        when(bookingRepository.findByBookingRefForUpdate("RB-ORDER-REUSE")).thenReturn(Optional.of(booking));
        when(allocationRepository.findById(17L)).thenReturn(Optional.of(allocation));
        when(paymentTransactionRepository.findFirstByBookingRefAndPaymentMethodAndStatusOrderByCreatedAtDesc(
                "RB-ORDER-REUSE", "RAZORPAY", "PENDING")).thenReturn(Optional.of(existing));

        var result = service.createOrder("RB-ORDER-REUSE", "alice");

        assertEquals("order_existing", result.orderId());
        verify(gatewayClient, never()).createOrder(any(Long.class), any(String.class));
    }

    @Test
    void createOrderRejectsMissingFareWithoutCallingGateway() {
        Booking booking = booking("RB-NO-FARE");
        booking.setAmount(null);
        when(bookingRepository.findByBookingRefForUpdate("RB-NO-FARE")).thenReturn(Optional.of(booking));

        assertThrows(IllegalStateException.class, () -> service.createOrder("RB-NO-FARE", "alice"));
        verify(gatewayClient, never()).createOrder(any(Long.class), any(String.class));
    }

    @Test
    void createOrderEnforcesBookingOwnership() {
        Booking booking = booking("RB-OTHER");
        when(bookingRepository.findByBookingRefForUpdate("RB-OTHER")).thenReturn(Optional.of(booking));

        assertThrows(AccessDeniedException.class, () -> service.createOrder("RB-OTHER", "mallory"));
        verify(gatewayClient, never()).createOrder(any(Long.class), any(String.class));
    }

    @Test
    void checkoutSignatureIsValidatedBeforeGatewayPaymentLookup() throws Exception {
        String signature = hmac("order_1|pay_1", "api-secret");
        service.verifyCheckoutSignature("order_1", "pay_1", signature);

        assertThrows(BadRequestException.class,
                () -> service.verifyCheckoutSignature("order_1", "pay_1", "invalid"));
        verify(gatewayClient, never()).fetchPayment(any(String.class));
    }

    @Test
    void validCheckoutSignatureAcceptsVerifiedPendingGatewayPayment() throws Exception {
        Booking booking = booking("RB-VERIFY");
        PaymentTransaction transaction = transaction(booking, "order_1");
        when(bookingRepository.findByBookingRefForUpdate("RB-VERIFY")).thenReturn(Optional.of(booking));
        when(paymentTransactionRepository.findByGatewayOrderIdForUpdate("order_1"))
                .thenReturn(Optional.of(transaction));
        when(gatewayClient.fetchPayment("pay_1"))
                .thenReturn(new RazorpayGatewayClient.GatewayPayment("pay_1", "order_1", 1234L, "INR", "authorized"));

        var result = service.verifyCheckout("RB-VERIFY", "alice",
                new RazorpayVerifyRequestDTO("order_1", "pay_1", hmac("order_1|pay_1", "api-secret")));

        assertEquals("PENDING", result.paymentStatus());
        assertEquals("PENDING_PAYMENT", result.bookingStatus());
    }

    @Test
    void duplicateWebhookEventIsIgnored() throws Exception {
        byte[] body = "{\"event\":\"subscription.activated\"}".getBytes(StandardCharsets.UTF_8);
        when(webhookEventRepository.existsById("evt_1")).thenReturn(false, true);

        service.processWebhook(body, hmac(body, "webhook-secret"), "evt_1");
        service.processWebhook(body, hmac(body, "webhook-secret"), "evt_1");

        verify(webhookEventRepository).save(any(RazorpayWebhookEvent.class));
    }

    @Test
    void invalidWebhookSignatureIsRejected() {
        assertThrows(BadRequestException.class, () -> service.processWebhook(
                "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8),
                "0".repeat(64),
                "evt_bad"));
        verify(webhookEventRepository, never()).save(any(RazorpayWebhookEvent.class));
    }

    @Test
    void latePaymentFailureDoesNotDowngradeCapturedPayment() throws Exception {
        Booking booking = booking("RB-ORDER-OUT-OF-ORDER");
        SeatAllocation allocation = activeHold();
        booking.setSeatAllocation(allocation);
        PaymentTransaction transaction = transaction(booking, "order_1");
        transaction.setPaymentId(10L);
        when(webhookEventRepository.existsById(any(String.class))).thenReturn(false);
        when(paymentTransactionRepository.findByGatewayOrderIdForUpdate("order_1"))
                .thenReturn(Optional.of(transaction));
        when(bookingRepository.findByBookingRefForUpdate(booking.getBookingRef()))
                .thenReturn(Optional.of(booking));
        when(allocationRepository.findById(17L)).thenReturn(Optional.of(allocation));
        when(paymentTransactionRepository.findByGatewayPaymentId("pay_1")).thenReturn(Optional.empty());

        byte[] captured = paymentWebhook("payment.captured", "order_1", "pay_1", "captured");
        service.processWebhook(captured, hmac(captured, "webhook-secret"), "evt_captured");
        byte[] failed = paymentWebhook("payment.failed", "order_1", "pay_1", "failed");
        service.processWebhook(failed, hmac(failed, "webhook-secret"), "evt_failed");

        assertEquals("SUCCESS", transaction.getStatus());
        assertEquals("CONFIRMED", booking.getBookingStatus());
        assertEquals("BOOKED", allocation.getStatus());
    }

    @Test
    void failedPaymentCanBeRetriedWithANewGatewayOrder() throws Exception {
        Booking booking = booking("RB-RETRY");
        SeatAllocation allocation = activeHold();
        booking.setSeatAllocation(allocation);
        PaymentTransaction failed = transaction(booking, "order_failed");
        failed.setStatus("FAILED");
        when(webhookEventRepository.existsById("evt_failed")).thenReturn(false);
        when(paymentTransactionRepository.findByGatewayOrderIdForUpdate("order_failed"))
                .thenReturn(Optional.of(failed));
        when(bookingRepository.findByBookingRefForUpdate("RB-RETRY")).thenReturn(Optional.of(booking));
        when(paymentTransactionRepository.findFirstByBookingRefAndPaymentMethodAndStatusOrderByCreatedAtDesc(
                "RB-RETRY", "RAZORPAY", "PENDING")).thenReturn(Optional.empty());
        when(allocationRepository.findById(17L)).thenReturn(Optional.of(allocation));
        when(gatewayClient.createOrder(eq(1234L), anyString()))
                .thenReturn(new RazorpayGatewayClient.GatewayOrder("order_retry", 1234L, "INR", "receipt"));
        when(paymentTransactionRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        byte[] failedWebhook = paymentWebhook("payment.failed", "order_failed", "pay_failed", "failed");
        service.processWebhook(failedWebhook, hmac(failedWebhook, "webhook-secret"), "evt_failed");
        var retryOrder = service.createOrder("RB-RETRY", "alice");

        assertEquals("FAILED", failed.getStatus());
        assertEquals("FAILED", booking.getPaymentStatus());
        assertEquals("order_retry", retryOrder.orderId());
    }

    @Test
    void gatewayPaymentIdCannotConfirmTwoDifferentOrders() throws Exception {
        Booking firstBooking = booking("RB-FIRST");
        Booking secondBooking = booking("RB-SECOND");
        SeatAllocation allocation = activeHold();
        firstBooking.setSeatAllocation(allocation);
        PaymentTransaction first = transaction(firstBooking, "order_first");
        first.setPaymentId(1L);
        PaymentTransaction second = transaction(secondBooking, "order_second");
        second.setPaymentId(2L);
        when(webhookEventRepository.existsById(anyString())).thenReturn(false);
        when(paymentTransactionRepository.findByGatewayOrderIdForUpdate("order_first"))
                .thenReturn(Optional.of(first));
        when(paymentTransactionRepository.findByGatewayOrderIdForUpdate("order_second"))
                .thenReturn(Optional.of(second));
        when(paymentTransactionRepository.findByGatewayPaymentId("pay_shared"))
                .thenReturn(Optional.empty(), Optional.of(first));
        when(bookingRepository.findByBookingRefForUpdate("RB-FIRST")).thenReturn(Optional.of(firstBooking));
        when(bookingRepository.findByBookingRefForUpdate("RB-SECOND")).thenReturn(Optional.of(secondBooking));
        when(allocationRepository.findById(17L)).thenReturn(Optional.of(allocation));

        byte[] firstEvent = paymentWebhook("payment.captured", "order_first", "pay_shared", "captured");
        service.processWebhook(firstEvent, hmac(firstEvent, "webhook-secret"), "evt_first");
        byte[] secondEvent = paymentWebhook("payment.captured", "order_second", "pay_shared", "captured");

        assertThrows(BadRequestException.class, () -> service.processWebhook(
                secondEvent, hmac(secondEvent, "webhook-secret"), "evt_second"));
        assertEquals("PENDING", second.getStatus());
    }

    @Test
    void processedRefundWebhookIsTheOnlyEventThatMarksRefundCompleted() throws Exception {
        Booking booking = booking("RB-REFUND");
        PaymentTransaction transaction = transaction(booking, "order_1");
        transaction.setPaymentId(25L);
        transaction.setGatewayPaymentId("pay_1");
        when(webhookEventRepository.existsById(any(String.class))).thenReturn(false);
        when(paymentTransactionRepository.findByGatewayPaymentId("pay_1")).thenReturn(Optional.of(transaction));
        when(refundRepository.findById("rfnd_1")).thenReturn(Optional.empty());
        when(refundRepository.save(any(RazorpayRefund.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(refundRepository.sumAmountByPaymentTransactionIdAndStatus(25L, "PROCESSED"))
                .thenReturn(new BigDecimal("12.34"));
        when(bookingRepository.findByBookingRefForUpdate("RB-REFUND")).thenReturn(Optional.of(booking));

        byte[] processed = refundWebhook("refund.processed");
        service.processWebhook(processed, hmac(processed, "webhook-secret"), "evt_refund_processed");

        ArgumentCaptor<RazorpayRefund> refundCaptor = ArgumentCaptor.forClass(RazorpayRefund.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertEquals("PROCESSED", refundCaptor.getValue().getStatus());
        assertEquals("REFUNDED", booking.getPaymentStatus());
    }

    @Test
    void disabledRazorpayIsRejectedBeforeDatabaseAccess() {
        properties.setEnabled(false);

        assertThrows(IllegalStateException.class, () -> service.createOrder("RB-ANY", "alice"));
        verify(bookingRepository, never()).findByBookingRefForUpdate(any(String.class));
    }

    private Booking booking(String bookingRef) {
        User user = new User();
        user.setUserId(3L);
        user.setUsername("alice");
        Booking booking = new Booking();
        booking.setBookingId(9L);
        booking.setBookingRef(bookingRef);
        booking.setPnr("1234567890");
        booking.setUser(user);
        Journey journey = new Journey();
        journey.setJourneyId(4L);
        booking.setJourney(journey);
        booking.setBookingStatus("PENDING_PAYMENT");
        booking.setPaymentStatus("PENDING");
        booking.setAmount(new BigDecimal("12.34"));
        return booking;
    }

    private SeatAllocation activeHold() {
        SeatAllocation allocation = new SeatAllocation();
        allocation.setSeatAllocationId(17L);
        allocation.setStatus("HOLD");
        allocation.setHoldExpiresAt(LocalDateTime.now().plusMinutes(5));
        return allocation;
    }

    private PaymentTransaction transaction(Booking booking, String orderId) {
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setPaymentId(10L);
        transaction.setBooking(booking);
        transaction.setBookingRef(booking.getBookingRef());
        transaction.setTransactionRef("RP-TXN-1");
        transaction.setPaymentMethod("RAZORPAY");
        transaction.setAmount(booking.getAmount());
        transaction.setStatus("PENDING");
        transaction.setGatewayOrderId(orderId);
        return transaction;
    }

    private byte[] paymentWebhook(String event, String orderId, String paymentId, String status) {
        String body = """
                {"event":"%s","payload":{"payment":{"entity":{"id":"%s","order_id":"%s","amount":1234,"currency":"INR","status":"%s"}}}}
                """.formatted(event, paymentId, orderId, status);
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] refundWebhook(String event) {
        return """
                {"event":"%s","payload":{"refund":{"entity":{"id":"rfnd_1","payment_id":"pay_1","amount":1234}}}}
                """.formatted(event).getBytes(StandardCharsets.UTF_8);
    }

    private String hmac(String value, String secret) throws Exception {
        return hmac(value.getBytes(StandardCharsets.UTF_8), secret);
    }

    private String hmac(byte[] value, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value));
    }
}
