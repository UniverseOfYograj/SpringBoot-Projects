package in.yograj.learnspringboot.railway_booking.dto;

import java.math.BigDecimal;

public record FareQuoteResponseDTO(
        Long journeyId,
        String travelClass,
        String className,
        Integer distanceKm,
        String distanceSource,
        Integer passengerCount,
        FareComponentDTO perPassengerFare,
        BigDecimal bookingTotal,
        String currency,
        BigDecimal ratePerKm
) {
}
