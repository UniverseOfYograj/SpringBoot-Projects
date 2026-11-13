package in.yograj.learnspringboot.railway_booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "railora.fare")
public class FareProperties {

    public static class ClassFarePolicy {
        private BigDecimal ratePerKm;
        private BigDecimal minBaseFare;
        private BigDecimal reservationCharge;
        private BigDecimal superfastCharge;
        private BigDecimal gstPercent;

        public ClassFarePolicy() {
        }

        public ClassFarePolicy(BigDecimal ratePerKm, BigDecimal minBaseFare, BigDecimal reservationCharge,
                               BigDecimal superfastCharge, BigDecimal gstPercent) {
            this.ratePerKm = ratePerKm;
            this.minBaseFare = minBaseFare;
            this.reservationCharge = reservationCharge;
            this.superfastCharge = superfastCharge;
            this.gstPercent = gstPercent;
        }

        public BigDecimal getRatePerKm() {
            return ratePerKm;
        }

        public void setRatePerKm(BigDecimal ratePerKm) {
            this.ratePerKm = ratePerKm;
        }

        public BigDecimal getMinBaseFare() {
            return minBaseFare;
        }

        public void setMinBaseFare(BigDecimal minBaseFare) {
            this.minBaseFare = minBaseFare;
        }

        public BigDecimal getReservationCharge() {
            return reservationCharge;
        }

        public void setReservationCharge(BigDecimal reservationCharge) {
            this.reservationCharge = reservationCharge;
        }

        public BigDecimal getSuperfastCharge() {
            return superfastCharge;
        }

        public void setSuperfastCharge(BigDecimal superfastCharge) {
            this.superfastCharge = superfastCharge;
        }

        public BigDecimal getGstPercent() {
            return gstPercent;
        }

        public void setGstPercent(BigDecimal gstPercent) {
            this.gstPercent = gstPercent;
        }
    }

    private String currency = "INR";
    private Integer defaultDistanceKm = 500;
    private Map<String, ClassFarePolicy> classes = new HashMap<>();

    public FareProperties() {
        initDefaults();
    }

    private void initDefaults() {
        // Sleeper (SL): 0% GST (non-AC)
        classes.put("SL", new ClassFarePolicy(
                new BigDecimal("0.45"),
                new BigDecimal("140.00"),
                new BigDecimal("20.00"),
                new BigDecimal("30.00"),
                BigDecimal.ZERO
        ));

        // AC 3 Tier (3A): 5% GST
        classes.put("3A", new ClassFarePolicy(
                new BigDecimal("1.15"),
                new BigDecimal("460.00"),
                new BigDecimal("40.00"),
                new BigDecimal("45.00"),
                new BigDecimal("5.00")
        ));

        // AC 2 Tier (2A): 5% GST
        classes.put("2A", new ClassFarePolicy(
                new BigDecimal("1.65"),
                new BigDecimal("750.00"),
                new BigDecimal("50.00"),
                new BigDecimal("45.00"),
                new BigDecimal("5.00")
        ));

        // AC First Class (1A): 5% GST
        classes.put("1A", new ClassFarePolicy(
                new BigDecimal("2.40"),
                new BigDecimal("1200.00"),
                new BigDecimal("60.00"),
                new BigDecimal("75.00"),
                new BigDecimal("5.00")
        ));

        // AC Chair Car (CC): 5% GST
        classes.put("CC", new ClassFarePolicy(
                new BigDecimal("0.90"),
                new BigDecimal("280.00"),
                new BigDecimal("40.00"),
                new BigDecimal("45.00"),
                new BigDecimal("5.00")
        ));

        // Executive Chair Car (EC): 5% GST
        classes.put("EC", new ClassFarePolicy(
                new BigDecimal("2.10"),
                new BigDecimal("900.00"),
                new BigDecimal("60.00"),
                new BigDecimal("45.00"),
                new BigDecimal("5.00")
        ));

        // Second Sitting (2S): 0% GST
        classes.put("2S", new ClassFarePolicy(
                new BigDecimal("0.25"),
                new BigDecimal("60.00"),
                new BigDecimal("15.00"),
                new BigDecimal("15.00"),
                BigDecimal.ZERO
        ));

        // AC 3 Economy (3E): 5% GST
        classes.put("3E", new ClassFarePolicy(
                new BigDecimal("1.05"),
                new BigDecimal("430.00"),
                new BigDecimal("40.00"),
                new BigDecimal("45.00"),
                new BigDecimal("5.00")
        ));

        // General / Unreserved (GEN): 0% GST
        classes.put("GEN", new ClassFarePolicy(
                new BigDecimal("0.20"),
                new BigDecimal("30.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));
    }

    public ClassFarePolicy getPolicyForClass(String travelClass) {
        if (travelClass == null || travelClass.isBlank()) {
            return classes.get("SL");
        }
        String normalized = travelClass.trim().toUpperCase();
        ClassFarePolicy policy = classes.get(normalized);
        if (policy != null) {
            return policy;
        }
        // Fallback default policy
        return classes.get("SL");
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Integer getDefaultDistanceKm() {
        return defaultDistanceKm;
    }

    public void setDefaultDistanceKm(Integer defaultDistanceKm) {
        this.defaultDistanceKm = defaultDistanceKm;
    }

    public Map<String, ClassFarePolicy> getClasses() {
        return classes;
    }

    public void setClasses(Map<String, ClassFarePolicy> classes) {
        this.classes = classes;
    }
}
