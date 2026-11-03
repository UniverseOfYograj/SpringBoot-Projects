package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.config.RailwayFareProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FareCalculationServiceTests {

    @Test
    void configuredFlatFareIsReturnedDeterministically() {
        RailwayFareProperties properties = new RailwayFareProperties();
        properties.setBaseAmount(new BigDecimal("525.50"));
        FareCalculationService service = new FareCalculationService(properties);

        assertEquals(new BigDecimal("525.50"), service.calculateFare());
        assertEquals(new BigDecimal("525.50"), service.calculateFare());
    }
}
