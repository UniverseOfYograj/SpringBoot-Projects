package in.yograj.learnspringboot.railway_booking.integration.railradar.mapper;

import in.yograj.learnspringboot.railway_booking.entity.Station;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.RailRadarStationDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.RailRadarTrainDetailsDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.dto.UnresolvedStationDTO;
import in.yograj.learnspringboot.railway_booking.integration.railradar.exception.RailRadarSyncException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class RailRadarMapper {

    private static final Set<String> EMPTY_TIMES = Set.of("", "-", "--", "source", "destination", "n/a");

    public RailRadarStationMapping mapStations(List<RailRadarStationDTO> externalStations) {
        if (externalStations == null) {
            throw badGateway("RailRadar station search returned no station data");
        }
        Map<String, Station> stationsByCode = new LinkedHashMap<>();
        Map<String, String> unresolvedByCode = new LinkedHashMap<>();
        int skipped = 0;
        for (RailRadarStationDTO external : externalStations) {
            if (external == null || external.code() == null || external.code().isBlank()) {
                skipped++;
                unresolvedByCode.putIfAbsent(null, "RailRadar result is missing the station code");
                continue;
            }

            String code = normalizeCode(external.code());
            if (code.length() > 20) {
                skipped++;
                unresolvedByCode.putIfAbsent(code, "Station code exceeds the local column length");
                continue;
            }
            String name = external.name() == null ? null : external.name().trim();
            String city = external.city() == null ? null : external.city().trim();
            String invalidReason = null;
            if (name != null && name.isBlank()) {
                name = null;
            }
            if (city != null && city.isBlank()) {
                city = null;
            }
            if (name != null && name.length() > 120) {
                invalidReason = "Station name exceeds the local column length";
            } else if (city != null && city.length() > 120) {
                invalidReason = "Station city exceeds the local column length";
            }
            if (invalidReason != null) {
                skipped++;
                if (stationsByCode.remove(code) != null) {
                    skipped++;
                }
                unresolvedByCode.put(code, invalidReason);
                continue;
            }

            Station imported = new Station();
            imported.setStationCode(code);
            imported.setStationName(name);
            imported.setCity(city);
            if (unresolvedByCode.containsKey(code)) {
                skipped++;
                continue;
            }
            Station previous = stationsByCode.putIfAbsent(code, imported);
            if (previous != null) {
                skipped++;
                if (!sameStationData(previous, imported)) {
                    skipped++;
                    stationsByCode.remove(code);
                    unresolvedByCode.put(code, "RailRadar returned conflicting records for this station code");
                }
            }
        }
        List<UnresolvedStationDTO> unresolved = unresolvedByCode.entrySet().stream()
                .map(entry -> new UnresolvedStationDTO(entry.getKey(), entry.getValue()))
                .toList();
        return new RailRadarStationMapping(
                externalStations.size(), skipped, List.copyOf(stationsByCode.values()), unresolved);
    }

    private boolean sameStationData(Station first, Station second) {
        return java.util.Objects.equals(first.getStationName(), second.getStationName())
                && java.util.Objects.equals(first.getCity(), second.getCity());
    }

    public RailRadarTrainImport mapTrain(String requestedNumber, RailRadarTrainDetailsDTO details) {
        String requested = required(requestedNumber, "train number");
        if (!requested.matches("\\d{5}")) {
            throw new RailRadarSyncException(HttpStatus.BAD_REQUEST,
                    "RailRadar train number must contain exactly five digits");
        }
        if (details == null || details.train() == null || details.route() == null || details.route().size() < 2) {
            throw badGateway("RailRadar train details must contain a train and at least two route stops");
        }
        String number = required(details.train().number(), "train number");
        if (!number.equals(requested)) {
            throw badGateway("RailRadar returned a different train number than requested");
        }
        String name = bounded(required(details.train().name(), "train name"), 120, "train name");

        List<RailRadarTrainDetailsDTO.RouteStop> externalStops = details.route().stream()
                .sorted(Comparator.comparing(stop -> stop == null || stop.sequence() == null
                        ? Integer.MAX_VALUE : stop.sequence()))
                .toList();
        LocalDateTime previousEvent = null;
        int firstStopDayOffset = 0;
        List<RailRadarTrainImport.Stop> stops = new java.util.ArrayList<>(externalStops.size());
        for (int index = 0; index < externalStops.size(); index++) {
            RailRadarTrainDetailsDTO.RouteStop stop = externalStops.get(index);
            if (stop == null || stop.station() == null || stop.sequence() == null
                    || stop.sequence() != index + 1) {
                throw badGateway("RailRadar route stop sequence must be continuous starting at 1");
            }
            String stationCode = bounded(
                    normalizeCode(required(stop.station().code(), "route station code")),
                    20, "station code");
            LocalTime arrival = parseTime(stop.arrival());
            LocalTime departure = parseTime(stop.departure());
            if (index == 0 && departure == null) {
                throw badGateway("RailRadar first route stop must have a departure time");
            }
            if (index == externalStops.size() - 1 && arrival == null) {
                throw badGateway("RailRadar last route stop must have an arrival time");
            }
            if (index > 0 && index < externalStops.size() - 1 && (arrival == null || departure == null)) {
                throw badGateway("RailRadar intermediate route stops require arrival and departure times");
            }

            int arrivalDay = dayOffset(stop.arrivalDay());
            int departureDay = dayOffset(stop.departureDay());
            if (arrival != null && departure != null && arrivalDay != departureDay) {
                throw new RailRadarSyncException(HttpStatus.CONFLICT,
                        "RailRadar stop has different arrival and departure day offsets, which the existing "
                                + "TrainStop model cannot represent");
            }
            if (index == 0) {
                firstStopDayOffset = arrival != null ? arrivalDay : departureDay;
            }
            if (arrival != null) {
                arrivalDay = normalizeDayOffset(arrivalDay, firstStopDayOffset);
            }
            if (departure != null) {
                departureDay = normalizeDayOffset(departureDay, firstStopDayOffset);
            }
            int dayOffset = arrival != null ? arrivalDay : departureDay;
            if (index == 0 && dayOffset != 0) {
                throw badGateway("RailRadar first route stop must use day offset 0");
            }
            LocalDateTime firstEvent = arrival == null ? null
                    : LocalDateTime.of(LocalDate.of(2000, 1, 1).plusDays(arrivalDay), arrival);
            if (firstEvent == null && departure != null) {
                firstEvent = LocalDateTime.of(LocalDate.of(2000, 1, 1).plusDays(departureDay), departure);
            }
            LocalDateTime departureEvent = departure == null ? firstEvent
                    : LocalDateTime.of(LocalDate.of(2000, 1, 1).plusDays(departureDay), departure);
            if (previousEvent != null && firstEvent.isBefore(previousEvent)) {
                throw badGateway("RailRadar route stop times are not chronological");
            }
            if (arrival != null && departure != null && departureEvent.isBefore(firstEvent)) {
                throw badGateway("RailRadar stop departure occurs before its arrival");
            }
            previousEvent = departureEvent;
            stops.add(new RailRadarTrainImport.Stop(index + 1, stationCode, arrival, departure, dayOffset));
        }
        return new RailRadarTrainImport(requested, name, List.copyOf(stops));
    }

    private int normalizeDayOffset(int dayOffset, int firstStopDayOffset) {
        int normalized = dayOffset - firstStopDayOffset;
        if (normalized < 0) {
            throw badGateway("RailRadar route stop day offset precedes the first stop");
        }
        return normalized;
    }

    private String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badGateway("RailRadar response is missing " + field);
        }
        return value.trim();
    }

    private String bounded(String value, int maxLength, String field) {
        if (value.length() > maxLength) {
            throw badGateway("RailRadar " + field + " exceeds the existing database column length");
        }
        return value;
    }

    private LocalTime parseTime(String value) {
        if (value == null || EMPTY_TIMES.contains(value.trim().toLowerCase(Locale.ROOT))) {
            return null;
        }
        String time = value.trim();
        try {
            return LocalTime.parse(time);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalTime.parse(time, DateTimeFormatter.ofPattern("H:mm", Locale.ROOT));
            } catch (DateTimeParseException exception) {
                throw badGateway("RailRadar returned an invalid timetable time");
            }
        }
    }

    private int dayOffset(Integer value) {
        int offset = value == null ? 0 : value;
        if (offset < 0) {
            throw badGateway("RailRadar returned a negative route day offset");
        }
        return offset;
    }

    private RailRadarSyncException badGateway(String message) {
        return new RailRadarSyncException(HttpStatus.BAD_GATEWAY, message);
    }
}
