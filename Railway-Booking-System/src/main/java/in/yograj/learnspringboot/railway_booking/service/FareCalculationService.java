package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.config.FareProperties;
import in.yograj.learnspringboot.railway_booking.config.RailwayFareProperties;
import in.yograj.learnspringboot.railway_booking.dto.FareComponentDTO;
import in.yograj.learnspringboot.railway_booking.dto.FareQuoteResponseDTO;
import in.yograj.learnspringboot.railway_booking.entity.Journey;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.repository.JourneyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@Transactional(readOnly = true)
public class FareCalculationService {

    private static final Logger logger = LoggerFactory.getLogger(FareCalculationService.class);

    private final JourneyRepository journeyRepository;
    private final DistanceResolverService distanceResolverService;
    private final FareProperties fareProperties;
    private final ObjectMapper objectMapper;
    private final BigDecimal legacyBaseAmount;

    @Autowired
    public FareCalculationService(
            JourneyRepository journeyRepository,
            DistanceResolverService distanceResolverService,
            FareProperties fareProperties,
            ObjectMapper objectMapper) {
        this.journeyRepository = journeyRepository;
        this.distanceResolverService = distanceResolverService;
        this.fareProperties = fareProperties;
        this.objectMapper = objectMapper;
        this.legacyBaseAmount = fareProperties != null && fareProperties.getPolicyForClass("SL") != null
                ? fareProperties.getPolicyForClass("SL").getMinBaseFare()
                : new BigDecimal("500.00");
    }

    public FareCalculationService(RailwayFareProperties properties) {
        properties.validate();
        this.legacyBaseAmount = properties.getBaseAmount();
        this.journeyRepository = null;
        this.distanceResolverService = null;
        this.fareProperties = null;
        this.objectMapper = null;
    }

    public FareCalculationService(BigDecimal baseAmount) {
        if (baseAmount == null || baseAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Base fare must be greater than zero");
        }
        try {
            this.legacyBaseAmount = baseAmount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Base fare must use no more than two decimal places", exception);
        }
        this.journeyRepository = null;
        this.distanceResolverService = null;
        this.fareProperties = null;
        this.objectMapper = null;
    }

    public BigDecimal calculateFare() {
        return legacyBaseAmount;
    }

    public FareQuoteResponseDTO calculateFareQuote(
            Long journeyId,
            String travelClass,
            Integer passengerCount,
            Long boardingStopId,
            Long alightingStopId) {

        if (journeyId == null) {
            throw new BadRequestException("Journey ID is required for fare calculation");
        }

        if (journeyRepository == null || distanceResolverService == null || fareProperties == null) {
            throw new IllegalStateException("Dynamic fare calculation engine is not configured in this context");
        }

        Journey journey = journeyRepository.findById(journeyId)
                .orElseThrow(() -> new ResourceNotFoundException("Journey not found for ID: " + journeyId));

        if (travelClass == null || travelClass.isBlank()) {
            throw new BadRequestException("Travel class is required for fare calculation");
        }

        String normalizedClass = normalizeClassCode(travelClass);
        if (normalizedClass == null || !fareProperties.getClasses().containsKey(normalizedClass)) {
            throw new BadRequestException("Unsupported travel class: " + travelClass);
        }

        if (passengerCount == null || passengerCount < 1) {
            throw new BadRequestException("Passenger count must be at least 1");
        }
        if (passengerCount > 6) {
            throw new BadRequestException("Passenger count must not exceed 6");
        }

        // 1. Resolve distance
        DistanceResolverService.ResolvedDistance resolved = distanceResolverService.resolveDistance(
                journey, boardingStopId, alightingStopId);
        int distanceKm = resolved.distanceKm();
        String distanceSource = resolved.distanceSource();

        // 2. Resolve policy
        FareProperties.ClassFarePolicy policy = fareProperties.getPolicyForClass(normalizedClass);

        // 3. Compute base fare
        BigDecimal rawBase = policy.getRatePerKm().multiply(BigDecimal.valueOf(distanceKm));
        BigDecimal baseFare = rawBase.max(policy.getMinBaseFare()).setScale(2, RoundingMode.HALF_UP);

        // 4. Additional components
        BigDecimal reservationCharge = policy.getReservationCharge().setScale(2, RoundingMode.HALF_UP);
        BigDecimal superfastCharge = policy.getSuperfastCharge().setScale(2, RoundingMode.HALF_UP);

        BigDecimal subtotal = baseFare.add(reservationCharge).add(superfastCharge);

        // 5. Tax / GST
        BigDecimal taxRate = policy.getGstPercent().divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
        BigDecimal tax = subtotal.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);

        // 6. Per passenger & booking totals
        BigDecimal perPassengerTotal = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
        BigDecimal bookingTotal = perPassengerTotal.multiply(BigDecimal.valueOf(passengerCount))
                .setScale(2, RoundingMode.HALF_UP);

        FareComponentDTO perPassenger = new FareComponentDTO(
                baseFare,
                reservationCharge,
                superfastCharge,
                tax,
                perPassengerTotal
        );

        return new FareQuoteResponseDTO(
                journey.getJourneyId(),
                normalizedClass,
                SeatInventoryService.getClassName(normalizedClass),
                distanceKm,
                distanceSource,
                passengerCount,
                perPassenger,
                bookingTotal,
                fareProperties.getCurrency(),
                policy.getRatePerKm()
        );
    }

    public String serializeFareBreakdown(FareQuoteResponseDTO quote) {
        if (quote == null || objectMapper == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(quote);
        } catch (Exception ex) {
            logger.warn("Failed to serialize fare breakdown snapshot: {}", ex.getMessage());
            return null;
        }
    }

    public FareComponentDTO extractPerPassengerBreakdown(String json) {
        if (json == null || json.isBlank() || objectMapper == null) {
            return null;
        }
        try {
            FareQuoteResponseDTO quote = objectMapper.readValue(json, FareQuoteResponseDTO.class);
            return quote != null ? quote.perPassengerFare() : null;
        } catch (Exception ex) {
            logger.debug("Could not deserialize fare breakdown snapshot JSON: {}", ex.getMessage());
            return null;
        }
    }

    public String normalizeClassCode(String travelClass) {
        if (travelClass == null || travelClass.isBlank()) {
            return "SL";
        }
        String clean = travelClass.trim().toUpperCase().replace("-", "_").replace(" ", "_");
        if (fareProperties != null && fareProperties.getClasses() != null && fareProperties.getClasses().containsKey(clean)) {
            return clean;
        }
        return switch (clean) {
            case "SECOND_SEATING", "SECOND_SITTING", "2_S", "2SITTING", "SECOND" -> "2S";
            case "SLEEPER", "SLP" -> "SL";
            case "AC_3_TIER", "AC_THREE_TIER", "3_TIER", "3TIER", "3AC", "AC3" -> "3A";
            case "AC_2_TIER", "AC_TWO_TIER", "2_TIER", "2TIER", "2AC", "AC2" -> "2A";
            case "FIRST_AC", "AC_FIRST_CLASS", "1_TIER", "1AC", "AC1" -> "1A";
            case "CHAIR_CAR", "AC_CHAIR_CAR" -> "CC";
            case "EXECUTIVE_CHAIR_CAR", "EXEC_CHAIR_CAR" -> "EC";
            case "3_ECONOMY", "AC_3_ECONOMY" -> "3E";
            case "GENERAL", "GEN", "UNRESERVED" -> "GEN";
            default -> null;
        };
    }
}
