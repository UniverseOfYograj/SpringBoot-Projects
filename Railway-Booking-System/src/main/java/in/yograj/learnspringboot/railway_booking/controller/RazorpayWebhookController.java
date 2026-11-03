package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.service.RazorpayPaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks/razorpay")
public class RazorpayWebhookController {

    private final RazorpayPaymentService razorpayPaymentService;

    public RazorpayWebhookController(RazorpayPaymentService razorpayPaymentService) {
        this.razorpayPaymentService = razorpayPaymentService;
    }

    @PostMapping
    public ResponseEntity<Void> receiveWebhook(
            @RequestBody byte[] rawBody,
            @RequestHeader("X-Razorpay-Signature") String signature,
            @RequestHeader("X-Razorpay-Event-Id") String eventId) {
        razorpayPaymentService.processWebhook(rawBody, signature, eventId);
        return ResponseEntity.ok().build();
    }
}
