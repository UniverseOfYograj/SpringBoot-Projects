import { useContext } from 'react';
import { useQuery, QueryClient, QueryClientContext } from '@tanstack/react-query';
import { bookingApi } from '@/features/booking/api';
import type { JourneyDTO } from '@/features/booking/types';

export interface UseOperationalJourneysOptions {
  enabled?: boolean;
}

// Fallback client ensuring isolated unit test harnesses (which don't provide QueryClientProvider)
// render gracefully without throwing runtime errors.
const fallbackQueryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      staleTime: 5 * 60 * 1000,
    },
  },
});

/**
 * Hook to resolve active operational journey runs for a travel date.
 *
 * Uses TanStack Query caching to eliminate N+1 API calls from individual TrainCards.
 * All cards on the search results page share a single cached request.
 */
export function useOperationalJourneys(
  travelDate?: string,
  options: UseOperationalJourneysOptions = {},
) {
  const contextClient = useContext(QueryClientContext);
  const hasQueryClient = Boolean(contextClient);
  const clientToUse = contextClient || fallbackQueryClient;

  const isEnabled = hasQueryClient && (options.enabled ?? true);

  const query = useQuery<JourneyDTO[], Error>(
    {
      queryKey: ['journeys', 'by-date', travelDate || 'all'],
      queryFn: () => bookingApi.getJourneys(travelDate ? { date: travelDate } : undefined),
      staleTime: 5 * 60 * 1000, // Operational schedules are cached for 5 minutes
      enabled: isEnabled,
    },
    clientToUse,
  );

  const journeys = query.data ?? [];

  const journeyByTrainId = new Map<number, JourneyDTO>();
  const journeyByTrainNumber = new Map<string, JourneyDTO>();

  for (const j of journeys) {
    if (j.trainId != null) {
      journeyByTrainId.set(j.trainId, j);
    }
    if (j.trainNumber) {
      journeyByTrainNumber.set(j.trainNumber.trim(), j);
    }
  }

  const findJourney = (trainId: number, trainNumber?: string): JourneyDTO | undefined => {
    return journeyByTrainId.get(trainId) || (trainNumber ? journeyByTrainNumber.get(trainNumber.trim()) : undefined);
  };

  return {
    ...query,
    journeys,
    journeyByTrainId,
    journeyByTrainNumber,
    findJourney,
    hasQueryClient,
    hasLoaded: query.isSuccess || query.isError,
  };
}
