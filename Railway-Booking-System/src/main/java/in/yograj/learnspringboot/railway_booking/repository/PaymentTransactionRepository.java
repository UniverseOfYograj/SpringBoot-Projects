package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.PaymentTransaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    List<PaymentTransaction> findByBookingRef(String bookingRef);

    Optional<PaymentTransaction> findByBookingRefAndIdempotencyKey(String bookingRef, String idempotencyKey);

    Optional<PaymentTransaction> findByTransactionRef(String transactionRef);

    Optional<PaymentTransaction> findByGatewayOrderId(String gatewayOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentTransaction p WHERE p.gatewayOrderId = :gatewayOrderId")
    Optional<PaymentTransaction> findByGatewayOrderIdForUpdate(@Param("gatewayOrderId") String gatewayOrderId);

    Optional<PaymentTransaction> findByGatewayPaymentId(String gatewayPaymentId);

    Optional<PaymentTransaction> findFirstByBookingRefAndPaymentMethodAndStatusOrderByCreatedAtDesc(
            String bookingRef, String paymentMethod, String status);
}
