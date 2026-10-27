package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.exception.QueueUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class TatkalJourneyPolicy {
    private final Set<Long> tatkalJourneyIds;

    public TatkalJourneyPolicy(@Value("${tatkal.queue.journey-ids:}") String configuredJourneyIds) {
        this.tatkalJourneyIds = Arrays.stream(configuredJourneyIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> {
                    try {
                        return Long.parseLong(value);
                    } catch (NumberFormatException exception) {
                        throw new IllegalStateException("tatkal.queue.journey-ids must contain comma-separated journey IDs",
                                exception);
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isTatkalJourney(long journeyId) {
        return tatkalJourneyIds.contains(journeyId);
    }

    public void requireTatkalJourney(long journeyId) {
        if (!isTatkalJourney(journeyId)) {
            throw new QueueUnavailableException("Tatkal queue is not configured for this journey");
        }
    }
}
