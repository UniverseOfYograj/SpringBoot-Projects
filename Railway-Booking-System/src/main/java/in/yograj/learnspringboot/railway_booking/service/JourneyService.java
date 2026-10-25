package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.dto.JourneyRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.JourneyResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.JourneyStopResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.SearchJourneyRequestDTO;

import java.time.LocalDate;
import java.util.List;

public interface JourneyService {
    JourneyResponseDTO createJourney(JourneyRequestDTO request);

    JourneyResponseDTO getJourneyById(Long journeyId);

    List<JourneyStopResponseDTO> getJourneyStops(Long journeyId);

    List<JourneyResponseDTO> searchJourneys(SearchJourneyRequestDTO request);

    List<JourneyResponseDTO> searchJourneys(Long fromStationId, Long toStationId, LocalDate journeyDate);

    List<JourneyResponseDTO> getAllJourneys();
}
