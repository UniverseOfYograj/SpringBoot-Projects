package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.dto.TrainDetailResponseDTO;
import in.yograj.learnspringboot.railway_booking.dto.TrainSearchResponseDTO;

import java.util.List;

public interface TrainSearchService {

    List<TrainSearchResponseDTO> searchTrains(String fromCode, String toCode);

    List<TrainSearchResponseDTO> searchTrainsUnified(String query, String fromCode, String toCode, int page, int size);

    TrainDetailResponseDTO getTrainByNumber(String trainNumber);
}
