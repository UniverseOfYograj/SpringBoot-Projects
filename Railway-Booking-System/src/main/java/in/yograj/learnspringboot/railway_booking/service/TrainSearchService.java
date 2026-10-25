package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.dto.TrainSearchResponseDTO;

import java.util.List;

public interface TrainSearchService {

    List<TrainSearchResponseDTO> searchTrains(String fromCode, String toCode);
}