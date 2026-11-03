package in.yograj.learnspringboot.railway_booking.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "razorpay")
public class RazorpayProperties {

    private boolean enabled;
    private String keyId = "";
    private String keySecret = "";
    private String webhookSecret = "";

    @PostConstruct
    void validateEnabledConfiguration() {
        if (!enabled) {
            return;
        }
        if (keyId == null || !keyId.startsWith("rzp_test_")
                || keySecret == null || keySecret.isBlank()
                || webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException(
                    "Razorpay requires a Test Mode key ID, API secret, and webhook secret when enabled");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getKeySecret() {
        return keySecret;
    }

    public void setKeySecret(String keySecret) {
        this.keySecret = keySecret;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }
}
