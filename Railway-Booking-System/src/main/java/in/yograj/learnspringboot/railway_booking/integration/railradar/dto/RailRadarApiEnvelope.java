package in.yograj.learnspringboot.railway_booking.integration.railradar.dto;

public record RailRadarApiEnvelope<T>(
        boolean success,
        T data,
        Object meta
) {
}
