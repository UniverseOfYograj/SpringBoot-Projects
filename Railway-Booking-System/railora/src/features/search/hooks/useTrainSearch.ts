import { useQuery } from '@tanstack/react-query';
import axios from 'axios';
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
  queryOrOptions?: string | UseTrainSearchOptions,
  maybeOptions?: UseTrainSearchOptions,
) {
  const queryStr = typeof queryOrOptions === 'string' ? queryOrOptions.trim() : '';
  const options = (typeof queryOrOptions === 'object' && queryOrOptions !== null ? queryOrOptions : maybeOptions) || {};

  const normalizedFrom = fromCode.trim().toUpperCase();
  const normalizedTo = toCode.trim().toUpperCase();

  const isFromValid = validateStationCode(normalizedFrom) === null;
  const isToValid = validateStationCode(normalizedTo) === null;
  const isDifferent = normalizedFrom !== normalizedTo;

  const isStationSearch = isFromValid && isToValid && isDifferent;
  const isQuerySearch = Boolean(queryStr && queryStr.length >= 2);

  const canExecute = isStationSearch || isQuerySearch;
  const isExplicitlyEnabled = options.enabled ?? true;

  return useQuery<TrainSearchResponseDTO[], Error>({
    queryKey: ['trains', 'search', normalizedFrom, normalizedTo, queryStr],
    queryFn: ({ signal }) => {
      if (isStationSearch) {
        return searchTrains(normalizedFrom, normalizedTo, signal);
      }
      return searchTrains({ q: queryStr }, signal);
    },
    enabled: isExplicitlyEnabled && canExecute,
    staleTime: 5 * 60 * 1000, // Timetable data is stable for 5 minutes
    retry: (failureCount, error: unknown) => {
      // Don't retry aborted/cancelled requests
      if (
        (error instanceof Error && (error.name === 'CanceledError' || error.name === 'AbortError')) ||
        (typeof error === 'object' && error !== null && 'name' in error && (error.name === 'CanceledError' || error.name === 'AbortError'))
      ) {
        return false;
      }
      // Don't retry timeout errors (Axios ECONNABORTED or timeout message) to prevent retry storms
      if (
        (axios.isAxiosError(error) && (error.code === 'ECONNABORTED' || error.message?.toLowerCase().includes('timeout'))) ||
        (typeof error === 'object' && error !== null && 'code' in error && error.code === 'ECONNABORTED') ||
        (error instanceof Error && error.message.toLowerCase().includes('timeout'))
      ) {
        return false;
      }
      // Don't retry client 4xx validation errors; retry 1x on network/5xx
      if (axios.isAxiosError(error) && error.response?.status && error.response.status >= 400 && error.response.status < 500) {
        return false;
      }
      return failureCount < 1;
    },
  });
}
