package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    List<PaymentTransaction> findByBookingRef(String bookingRef);

    Optional<PaymentTransaction> findByBookingRefAndIdempotencyKey(String bookingRef, String idempotencyKey);

    Optional<PaymentTransaction> findByTransactionRef(String transactionRef);
}
