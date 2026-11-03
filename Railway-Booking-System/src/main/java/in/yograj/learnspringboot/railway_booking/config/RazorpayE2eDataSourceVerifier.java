package in.yograj.learnspringboot.railway_booking.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
@Profile("razorpay-e2e")
public class RazorpayE2eDataSourceVerifier {

    public RazorpayE2eDataSourceVerifier(
            @Value("${spring.datasource.url}") String datasourceUrl,
            DataSource dataSource,
            @Value("${railway.payment.simulated-enabled:false}") boolean simulatedEnabled,
            RazorpayProperties razorpayProperties) {
        if (!razorpayProperties.isEnabled() && !simulatedEnabled) {
            throw new IllegalStateException(
                    "The razorpay-e2e profile requires RAZORPAY_ENABLED=true or RAILWAY_PAYMENT_SIMULATED_ENABLED=true");
        }
        RazorpayE2eDataSourceGuard.verify(datasourceUrl, dataSource);
    }
}
