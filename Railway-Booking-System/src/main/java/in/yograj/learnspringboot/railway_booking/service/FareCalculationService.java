package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.config.RailwayFareProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class FareCalculationService {

    private final BigDecimal baseAmount;

    @Autowired
    public FareCalculationService(RailwayFareProperties properties) {
        properties.validate();
        this.baseAmount = properties.getBaseAmount();
    }

    public FareCalculationService(BigDecimal baseAmount) {
        if (baseAmount == null || baseAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Base fare must be greater than zero");
        }
        try {
            this.baseAmount = baseAmount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Base fare must use no more than two decimal places", exception);
        }
    }

    public BigDecimal calculateFare() {
        return baseAmount;
    }
}
