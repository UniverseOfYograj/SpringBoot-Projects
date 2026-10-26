/**
 * Train search types strictly matching the backend Spring Boot contract:
 * in.yograj.learnspringboot.railway_booking.dto.TrainSearchResponseDTO
 */
export interface TrainSearchResponseDTO {
  trainId: number;
  trainNumber: string;
  trainName: string;

  fromStationCode: string;
  fromStationName: string;
  fromArrivalTime: string | null;
  fromDepartureTime: string | null;
  fromDayOffset: number;

  toStationCode: string;
  toStationName: string;
  toArrivalTime: string | null;
  toDepartureTime: string | null;
  toDayOffset: number;
}

export interface TrainSearchParams {
  from: string;
  to: string;
  date?: string;
}
