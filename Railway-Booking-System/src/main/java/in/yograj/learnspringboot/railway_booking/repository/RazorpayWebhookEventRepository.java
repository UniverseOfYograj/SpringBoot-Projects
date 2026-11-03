package in.yograj.learnspringboot.railway_booking.repository;

import in.yograj.learnspringboot.railway_booking.entity.RazorpayWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RazorpayWebhookEventRepository extends JpaRepository<RazorpayWebhookEvent, String> {
}
