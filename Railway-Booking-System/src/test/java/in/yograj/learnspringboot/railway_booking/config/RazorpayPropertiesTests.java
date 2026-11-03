package in.yograj.learnspringboot.railway_booking.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RazorpayPropertiesTests {

    @Test
    void disabledConfigurationDoesNotRequireCredentials() {
        RazorpayProperties properties = new RazorpayProperties();

        assertDoesNotThrow(properties::validateEnabledConfiguration);
    }

    @Test
    void enabledConfigurationRequiresTestCredentials() {
        RazorpayProperties properties = new RazorpayProperties();
        properties.setEnabled(true);

        assertThrows(IllegalStateException.class, properties::validateEnabledConfiguration);
    }

    @Test
    void enabledConfigurationRejectsLiveKeyId() {
        RazorpayProperties properties = configuredProperties("rzp_live_public", "api-secret", "webhook-secret");

        assertThrows(IllegalStateException.class, properties::validateEnabledConfiguration);
    }

    @Test
    void enabledConfigurationAcceptsTestKeyAndBothSecrets() {
        RazorpayProperties properties = configuredProperties("rzp_test_public", "api-secret", "webhook-secret");

        assertDoesNotThrow(properties::validateEnabledConfiguration);
    }

    private static RazorpayProperties configuredProperties(String keyId, String keySecret, String webhookSecret) {
        RazorpayProperties properties = new RazorpayProperties();
        properties.setEnabled(true);
        properties.setKeyId(keyId);
        properties.setKeySecret(keySecret);
        properties.setWebhookSecret(webhookSecret);
        return properties;
    }
}
