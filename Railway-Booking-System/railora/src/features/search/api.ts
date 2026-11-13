import apiClient from '@/lib/api-client';
import { API_PATHS } from '@/lib/constants';
import type { TrainSearchResponseDTO, SearchTrainsOptions } from './types';

/**
 * Searches for trains between origin and destination stations or by train number/name.
 *
 * Calls Spring Boot backend:
 * GET /api/trains/search?from={from}&to={to}
 * GET /api/trains/search?q={query}
 *
 * Supports both station-pair searches and unified direct train searches.
 * Supports optional AbortSignal for request cancellation during rapid searches.
 */
export async function searchTrains(
  fromOrOptions: string | SearchTrainsOptions,
  toOrSignal?: string | AbortSignal,
  maybeSignal?: AbortSignal,
): Promise<TrainSearchResponseDTO[]> {
  const params: Record<string, unknown> = {};
  let signal: AbortSignal | undefined;

  if (typeof fromOrOptions === 'object' && fromOrOptions !== null) {
    const { from, to, q, query, page, size } = fromOrOptions;
    signal = toOrSignal instanceof AbortSignal ? toOrSignal : maybeSignal;
    if (from) params.from = from.trim().toUpperCase();
    if (to) params.to = to.trim().toUpperCase();
    if (q) params.q = q.trim();
    if (query) params.query = query.trim();
    if (page !== undefined) params.page = page;
    if (size !== undefined) params.size = size;
  } else if (typeof fromOrOptions === 'string') {
    const from = fromOrOptions.trim().toUpperCase();
    const to = typeof toOrSignal === 'string' ? toOrSignal.trim().toUpperCase() : '';
    signal = maybeSignal || (toOrSignal instanceof AbortSignal ? toOrSignal : undefined);
    if (from) params.from = from;
    if (to) params.to = to;
  }

  const response = await apiClient.get<TrainSearchResponseDTO[]>(
    API_PATHS.TRAINS.SEARCH,
    {
      params,
      signal,
    },
  );

  return response.data;
}
