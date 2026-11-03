package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.RazorpayRefund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface RazorpayRefundRepository extends JpaRepository<RazorpayRefund, String> {
    List<RazorpayRefund> findByPaymentTransaction_PaymentId(Long paymentId);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM RazorpayRefund r "
            + "WHERE r.paymentTransaction.paymentId = :paymentId AND r.status = :status")
    BigDecimal sumAmountByPaymentTransactionIdAndStatus(
            @Param("paymentId") Long paymentId, @Param("status") String status);
}
