import apiClient from '@/lib/api-client';
import { API_PATHS } from '@/lib/constants';
import type { TrainSearchResponseDTO } from './types';

/**
 * Searches for direct trains between origin and destination stations.
 *
 * Calls Spring Boot backend:
 * GET /api/trains/search?from={from}&to={to}
 *
 * Both station codes are normalized (trimmed and uppercased) prior to request.
 */
export async function searchTrains(
  fromStationCode: string,
  toStationCode: string,
): Promise<TrainSearchResponseDTO[]> {
  const from = fromStationCode.trim().toUpperCase();
  const to = toStationCode.trim().toUpperCase();

  const response = await apiClient.get<TrainSearchResponseDTO[]>(
    API_PATHS.TRAINS.SEARCH,
    {
      params: { from, to },
    },
  );

  return response.data;
}
