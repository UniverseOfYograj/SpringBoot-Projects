package in.yograj.learnspringboot.railway_booking.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
@ConfigurationProperties(prefix = "railway.fare")
public class RailwayFareProperties {

    private BigDecimal baseAmount;

    @PostConstruct
    public void validate() {
        if (baseAmount == null || baseAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("railway.fare.base-amount must be greater than zero");
        }
        try {
            baseAmount = baseAmount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("railway.fare.base-amount must use no more than two decimal places");
        }
    }

    public BigDecimal getBaseAmount() {
        return baseAmount;
    }

    public void setBaseAmount(BigDecimal baseAmount) {
        this.baseAmount = baseAmount;
    }
}
