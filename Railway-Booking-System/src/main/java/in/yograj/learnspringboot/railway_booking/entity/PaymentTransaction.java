package in.yograj.learnspringboot.railway_booking.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_transactions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_booking_idempotency", columnNames = {"booking_ref", "idempotency_key"})
        },
        indexes = {
                @Index(name = "idx_payment_booking_ref", columnList = "booking_ref"),
                @Index(name = "idx_payment_transaction_ref", columnList = "transaction_ref")
        })
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long paymentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(name = "booking_ref", nullable = false, length = 40)
    private String bookingRef;

    @Column(name = "transaction_ref", nullable = false, unique = true, length = 64)
    private String transactionRef;

    @Column(name = "payment_method", nullable = false, length = 50)
    private String paymentMethod;

    @Column(name = "amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "simulate_failure")
    private Boolean simulateFailure;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public PaymentTransaction() {
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(Long paymentId) {
        this.paymentId = paymentId;
    }

    public Booking getBooking() {
        return booking;
    }

    public void setBooking(Booking booking) {
        this.booking = booking;
    }

    public String getBookingRef() {
        return bookingRef;
    }

    public void setBookingRef(String bookingRef) {
        this.bookingRef = bookingRef;
    }

    public String getTransactionRef() {
        return transactionRef;
    }

    public void setTransactionRef(String transactionRef) {
        this.transactionRef = transactionRef;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Boolean getSimulateFailure() {
        return simulateFailure;
    }

    public void setSimulateFailure(Boolean simulateFailure) {
        this.simulateFailure = simulateFailure;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
