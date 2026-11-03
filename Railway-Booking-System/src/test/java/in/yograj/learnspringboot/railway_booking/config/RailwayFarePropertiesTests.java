package in.yograj.learnspringboot.railway_booking.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RailwayFarePropertiesTests {

    @Test
    void missingFareConfigurationIsRejected() {
        RailwayFareProperties properties = new RailwayFareProperties();

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void nonPositiveFareConfigurationIsRejected() {
        RailwayFareProperties properties = new RailwayFareProperties();
        properties.setBaseAmount(BigDecimal.ZERO);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void fareWithMoreThanTwoFractionDigitsIsRejected() {
        RailwayFareProperties properties = new RailwayFareProperties();
        properties.setBaseAmount(new BigDecimal("500.001"));

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void positiveInrFareWithTwoFractionDigitsIsAccepted() {
        RailwayFareProperties properties = new RailwayFareProperties();
        properties.setBaseAmount(new BigDecimal("525.50"));

        assertDoesNotThrow(properties::validate);
    }
}
