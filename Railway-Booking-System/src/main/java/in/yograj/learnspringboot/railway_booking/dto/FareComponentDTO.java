package in.yograj.learnspringboot.railway_booking.dto;

import java.math.BigDecimal;

public record FareComponentDTO(
        BigDecimal baseFare,
        BigDecimal reservationCharge,
        BigDecimal superfastCharge,
        BigDecimal tax,
        BigDecimal total
) {
}
