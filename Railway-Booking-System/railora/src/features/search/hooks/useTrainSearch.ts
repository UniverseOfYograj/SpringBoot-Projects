import { useQuery } from '@tanstack/react-query';
import { searchTrains } from '../api';
import { validateStationCode } from '../utils';
import type { TrainSearchResponseDTO } from '../types';

export interface UseTrainSearchOptions {
  enabled?: boolean;
}

/**
 * TanStack Query hook for train search.
 *
 * Query key: ['trains', 'search', normalizedFrom, normalizedTo]
 * Automatically cancels stale in-flight requests via AbortSignal.
 * Disables query execution if either station code is invalid or if they match.
 */
export function useTrainSearch(
  fromCode: string,
  toCode: string,
  options: UseTrainSearchOptions = {},
) {
  const normalizedFrom = fromCode.trim().toUpperCase();
  const normalizedTo = toCode.trim().toUpperCase();

  const isFromValid = validateStationCode(normalizedFrom) === null;
  const isToValid = validateStationCode(normalizedTo) === null;
  const isDifferent = normalizedFrom !== normalizedTo;

  const canExecute = isFromValid && isToValid && isDifferent;
  const isExplicitlyEnabled = options.enabled ?? true;

  return useQuery<TrainSearchResponseDTO[], Error>({
    queryKey: ['trains', 'search', normalizedFrom, normalizedTo],
    queryFn: ({ signal }) => searchTrains(normalizedFrom, normalizedTo, signal),
    enabled: isExplicitlyEnabled && canExecute,
    staleTime: 5 * 60 * 1000, // Timetable data is stable for 5 minutes
    retry: (failureCount, error: any) => {
      // Don't retry aborted/cancelled requests
      if (error?.name === 'CanceledError' || error?.name === 'AbortError') {
        return false;
      }
      // Don't retry client 4xx validation errors; retry 1x on network/5xx
      if (error?.response?.status && error.response.status >= 400 && error.response.status < 500) {
        return false;
      }
      return failureCount < 1;
    },
  });
}
