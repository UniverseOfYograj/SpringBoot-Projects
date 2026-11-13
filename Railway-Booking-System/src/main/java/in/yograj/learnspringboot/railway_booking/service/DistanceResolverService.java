package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.config.FareProperties;
import in.yograj.learnspringboot.railway_booking.entity.Journey;
import in.yograj.learnspringboot.railway_booking.entity.JourneyStop;
import in.yograj.learnspringboot.railway_booking.exception.BadRequestException;
import in.yograj.learnspringboot.railway_booking.repository.JourneyStopRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class DistanceResolverService {

    public record ResolvedDistance(int distanceKm, String distanceSource) {
    }

    private final JourneyStopRepository journeyStopRepository;
    private final FareProperties fareProperties;

    // Explicit portfolio demo corridor distance assumptions (in km).
    // Note: These represent curated demo distances for portfolio demonstration,
    // not official IRCTC distance table lookups.
    private final Map<String, Integer> knownCorridors = new HashMap<>();

    public DistanceResolverService(JourneyStopRepository journeyStopRepository, FareProperties fareProperties) {
        this.journeyStopRepository = journeyStopRepository;
        this.fareProperties = fareProperties;
        initKnownCorridors();
    }

    private void initKnownCorridors() {
        // Grand Chord Howrah - New Delhi corridor (Train 12301/12302 demo configuration)
        addCorridor("HWH", "NDLS", 1447);
        // Chennai Central - KSR Bengaluru corridor (demo configuration)
        addCorridor("MAS", "SBC", 360);
        // Chennai Central - Coimbatore Junction corridor (demo configuration)
        addCorridor("MAS", "CBE", 495);
        // Mumbai CSMT - New Delhi corridor (demo configuration)
        addCorridor("CSMT", "NDLS", 1543);
    }

    private void addCorridor(String fromCode, String toCode, int distanceKm) {
        knownCorridors.put(corridorKey(fromCode, toCode), distanceKm);
        knownCorridors.put(corridorKey(toCode, fromCode), distanceKm);
    }

    private String corridorKey(String fromCode, String toCode) {
        return (fromCode != null ? fromCode.trim().toUpperCase() : "") + "-"
                + (toCode != null ? toCode.trim().toUpperCase() : "");
    }

    public ResolvedDistance resolveDistance(Journey journey, Long boardingStopId, Long alightingStopId) {
        if (journey == null) {
            throw new BadRequestException("Cannot resolve travel distance: journey is null");
        }

        // 1. Try stop-level explicit distance metrics if both stops are provided
        if (boardingStopId != null && alightingStopId != null) {
            Optional<JourneyStop> bStopOpt = journeyStopRepository.findById(boardingStopId);
            Optional<JourneyStop> aStopOpt = journeyStopRepository.findById(alightingStopId);

            if (bStopOpt.isEmpty() || aStopOpt.isEmpty()) {
                throw new BadRequestException("Boarding or alighting stop not found for provided stop IDs");
            }

            JourneyStop bStop = bStopOpt.get();
            JourneyStop aStop = aStopOpt.get();

            if (bStop.getDistanceFromOriginKm() != null && aStop.getDistanceFromOriginKm() != null) {
                BigDecimal diff = aStop.getDistanceFromOriginKm().subtract(bStop.getDistanceFromOriginKm()).abs();
                int km = Math.max(1, diff.intValue());
                return new ResolvedDistance(km, "STOP_METRICS");
            }

            // Check segment station codes in known corridors
            String bCode = bStop.getStation() != null ? bStop.getStation().getStationCode() : null;
            String aCode = aStop.getStation() != null ? aStop.getStation().getStationCode() : null;
            Integer corridorDist = knownCorridors.get(corridorKey(bCode, aCode));
            if (corridorDist != null) {
                return new ResolvedDistance(corridorDist, "CONFIGURED_CORRIDOR");
            }

            // Fallback estimation using scheduled timetable duration or stop counts
            int estimatedKm = estimateDistance(bStop, aStop);
            return new ResolvedDistance(estimatedKm, "ESTIMATED_RUN_DURATION");
        }

        // 2. Journey endpoints in known corridors (when boarding/alighting stops are not explicitly provided)
        String fromCode = journey.getFromStation() != null ? journey.getFromStation().getStationCode() : null;
        String toCode = journey.getToStation() != null ? journey.getToStation().getStationCode() : null;
        Integer journeyCorridorDist = knownCorridors.get(corridorKey(fromCode, toCode));
        if (journeyCorridorDist != null) {
            return new ResolvedDistance(journeyCorridorDist, "CONFIGURED_CORRIDOR");
        }

        // 3. Fallback estimation for journey endpoints
        int estimatedJourneyKm = estimateJourneyDistance(journey);
        return new ResolvedDistance(estimatedJourneyKm, "ESTIMATED_RUN_DURATION");
    }

    private int estimateDistance(JourneyStop bStop, JourneyStop aStop) {
        if (bStop.getDepartureAt() != null && aStop.getArrivalAt() != null) {
            long minutes = java.time.Duration.between(bStop.getDepartureAt(), aStop.getArrivalAt()).toMinutes();
            if (minutes > 0) {
                int dist = (int) Math.round(minutes * (55.0 / 60.0));
                return Math.max(25, dist);
            }
        }
        int stopDiff = Math.abs(aStop.getStopSequence() - bStop.getStopSequence());
        return Math.max(25, stopDiff * 35);
    }

    private int estimateJourneyDistance(Journey journey) {
        if (journey.getDepartureTime() != null && journey.getArrivalTime() != null) {
            long minutes;
            if (journey.getArrivalTime().isAfter(journey.getDepartureTime())) {
                minutes = java.time.Duration.between(journey.getDepartureTime(), journey.getArrivalTime()).toMinutes();
            } else {
                // Overnight journey run
                minutes = java.time.Duration.between(journey.getDepartureTime(), java.time.LocalTime.MAX).toMinutes()
                        + java.time.Duration.between(java.time.LocalTime.MIN, journey.getArrivalTime()).toMinutes();
            }
            if (minutes > 0) {
                int dist = (int) Math.round(minutes * (55.0 / 60.0));
                return Math.max(25, dist);
            }
        }
        return fareProperties != null && fareProperties.getDefaultDistanceKm() != null
                ? fareProperties.getDefaultDistanceKm() : 500;
    }
}
