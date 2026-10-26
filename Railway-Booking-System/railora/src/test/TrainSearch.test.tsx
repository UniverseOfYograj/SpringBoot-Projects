import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { TrainSearchForm } from '@/features/search/components/TrainSearchForm';
import { TrainCard } from '@/features/search/components/TrainCard';
import { TrainList } from '@/features/search/components/TrainList';
import TrainSearchPage from '@/pages/TrainSearchPage';
import {
  validateStationCode,
  calculateDuration,
  formatTimeString,
} from '@/features/search/utils';
import type { TrainSearchResponseDTO } from '@/features/search/types';
import * as api from '@/features/search/api';

// Realistic fixture strictly matching Spring Boot TrainSearchResponseDTO
const MOCK_TRAINS: TrainSearchResponseDTO[] = [
  {
    trainId: 101,
    trainNumber: '12301',
    trainName: 'HOWRAH RAJDHANI',
    fromStationCode: 'HWH',
    fromStationName: 'HOWRAH JN',
    fromArrivalTime: '16:50:00',
    fromDepartureTime: '16:55:00',
    fromDayOffset: 0,
    toStationCode: 'NDLS',
    toStationName: 'NEW DELHI',
    toArrivalTime: '10:05:00',
    toDepartureTime: '10:05:00',
    toDayOffset: 1,
  },
  {
    trainId: 102,
    trainNumber: '12305',
    trainName: 'KOLKATA RAJDHANI',
    fromStationCode: 'HWH',
    fromStationName: 'HOWRAH JN',
    fromArrivalTime: '14:00:00',
    fromDepartureTime: '14:05:00',
    fromDayOffset: 0,
    toStationCode: 'NDLS',
    toStationName: 'NEW DELHI',
    toArrivalTime: '08:35:00',
    toDepartureTime: '08:35:00',
    toDayOffset: 1,
  },
];

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        staleTime: 0,
      },
    },
  });
}

describe('Train Search — Utility Functions', () => {
  describe('validateStationCode', () => {
    it('returns error when station code is empty', () => {
      expect(validateStationCode('')).toBe('Station code is required');
      expect(validateStationCode('   ')).toBe('Station code is required');
    });

    it('returns error when station code is too short', () => {
      expect(validateStationCode('A')).toBe('Station code must be at least 2 characters');
    });

    it('returns error when station code exceeds 5 characters', () => {
      expect(validateStationCode('ABCDEF')).toBe('Station code cannot exceed 5 characters');
    });

    it('returns error when station code contains non-letter characters', () => {
      expect(validateStationCode('ND1')).toBe('Station code must contain letters only');
      expect(validateStationCode('H-W')).toBe('Station code must contain letters only');
    });

    it('returns null for valid station codes', () => {
      expect(validateStationCode('NDLS')).toBeNull();
      expect(validateStationCode('HWH')).toBeNull();
      expect(validateStationCode('CSMT')).toBeNull();
      expect(validateStationCode('bct')).toBeNull(); // case normalization
    });
  });

  describe('calculateDuration', () => {
    it('calculates duration within the same day', () => {
      const duration = calculateDuration('10:00:00', '14:30:00', 0, 0);
      expect(duration).toBe('4h 30m');
    });

    it('calculates overnight duration with day offset', () => {
      const duration = calculateDuration('16:55:00', '10:05:00', 0, 1);
      expect(duration).toBe('17h 10m');
    });

    it('handles exact hour duration without minutes', () => {
      const duration = calculateDuration('08:00:00', '11:00:00', 0, 0);
      expect(duration).toBe('3h');
    });

    it('returns fallback for missing or invalid times', () => {
      expect(calculateDuration(null, '10:00:00')).toBe('--');
      expect(calculateDuration('10:00:00', null)).toBe('--');
      expect(calculateDuration('invalid', '10:00:00')).toBe('--');
    });
  });

  describe('formatTimeString', () => {
    it('trims seconds from HH:mm:ss format', () => {
      expect(formatTimeString('16:55:00')).toBe('16:55');
      expect(formatTimeString('08:05:30')).toBe('08:05');
    });

    it('handles null or undefined gracefully', () => {
      expect(formatTimeString(null)).toBe('--:--');
      expect(formatTimeString(undefined)).toBe('--:--');
    });
  });
});

describe('TrainSearchForm Component', () => {
  it('renders input fields with accessible labels and placeholders', () => {
    render(
      <TrainSearchForm onSearch={vi.fn()} />
    );

    expect(screen.getByLabelText(/^Origin Station/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Destination Station/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Travel Date/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Search Trains/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Swap origin and destination/i })).toBeInTheDocument();
  });

  it('validates required origin and destination on submit', async () => {
    const handleSearch = vi.fn();
    render(<TrainSearchForm onSearch={handleSearch} />);

    const submitBtn = screen.getByRole('button', { name: /Search Trains/i });
    fireEvent.click(submitBtn);

    expect(handleSearch).not.toHaveBeenCalled();
    const errors = screen.getAllByText(/Station code is required/i);
    expect(errors.length).toBeGreaterThanOrEqual(1);
  });

  it('rejects identical origin and destination station codes', async () => {
    const user = userEvent.setup();
    const handleSearch = vi.fn();
    render(<TrainSearchForm onSearch={handleSearch} />);

    const fromInput = screen.getByLabelText(/^Origin Station/i);
    const toInput = screen.getByLabelText(/^Destination Station/i);

    await user.type(fromInput, 'NDLS');
    await user.type(toInput, 'NDLS');

    const submitBtn = screen.getByRole('button', { name: /Search Trains/i });
    await user.click(submitBtn);

    expect(handleSearch).not.toHaveBeenCalled();
    expect(
      screen.getByText(/Origin and destination stations cannot be the same/i)
    ).toBeInTheDocument();
  });

  it('swaps origin and destination when clicking the swap button', async () => {
    const user = userEvent.setup();
    render(<TrainSearchForm initialFrom="HWH" initialTo="NDLS" onSearch={vi.fn()} />);

    const fromInput = screen.getByLabelText(/^Origin Station/i) as HTMLInputElement;
    const toInput = screen.getByLabelText(/^Destination Station/i) as HTMLInputElement;

    expect(fromInput.value).toBe('HWH');
    expect(toInput.value).toBe('NDLS');

    const swapBtn = screen.getByRole('button', { name: /Swap origin and destination/i });
    await user.click(swapBtn);

    expect(fromInput.value).toBe('NDLS');
    expect(toInput.value).toBe('HWH');
  });

  it('selects a popular route pill and submits search', async () => {
    const user = userEvent.setup();
    const handleSearch = vi.fn();
    render(<TrainSearchForm onSearch={handleSearch} />);

    const routePill = screen.getByRole('button', { name: /Howrah → New Delhi/i });
    await user.click(routePill);

    expect(handleSearch).toHaveBeenCalledWith(
      expect.objectContaining({
        from: 'HWH',
        to: 'NDLS',
      })
    );
  });
});

describe('TrainCard Component', () => {
  it('renders complete train details matching the verified schema', () => {
    render(<TrainCard train={MOCK_TRAINS[0]} travelDate="2026-11-01" />);

    // Train Identity
    expect(screen.getByText('12301')).toBeInTheDocument();
    expect(screen.getByText('HOWRAH RAJDHANI')).toBeInTheDocument();

    // Stations & Times
    expect(screen.getByText('HWH')).toBeInTheDocument();
    expect(screen.getByText('HOWRAH JN')).toBeInTheDocument();
    expect(screen.getByText('16:55')).toBeInTheDocument();

    expect(screen.getByText('NDLS')).toBeInTheDocument();
    expect(screen.getByText('NEW DELHI')).toBeInTheDocument();
    expect(screen.getByText('10:05')).toBeInTheDocument();

    // Duration & Day Offset
    expect(screen.getByText('17h 10m')).toBeInTheDocument();
    expect(screen.getByText('+1 Day')).toBeInTheDocument();

    // Travel Date context
    expect(screen.getByText(/Target: 2026-11-01/i)).toBeInTheDocument();

    // Stage 5 readiness button
    expect(screen.getByRole('button', { name: /Check Availability Stage 5/i })).toBeDisabled();
  });
});

describe('TrainList Component', () => {
  it('renders guidance view before user has searched', () => {
    render(
      <TrainList
        isLoading={false}
        isError={false}
        hasSearched={false}
        onRetry={vi.fn()}
      />
    );

    expect(screen.getByText(/Ready to explore train schedules/i)).toBeInTheDocument();
    expect(screen.getByText(/Real Timetable/i)).toBeInTheDocument();
  });

  it('renders loading skeletons and live region when loading', () => {
    render(
      <TrainList
        isLoading={true}
        isError={false}
        hasSearched={true}
        fromCode="HWH"
        toCode="NDLS"
        onRetry={vi.fn()}
      />
    );

    const statusRegion = screen.getByRole('status');
    expect(statusRegion).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText(/Searching scheduled trains between/i)).toBeInTheDocument();
  });

  it('renders error alert with retry button when error occurs', async () => {
    const handleRetry = vi.fn();
    const testError = new Error('Network timeout connecting to port 8080');

    render(
      <TrainList
        isLoading={false}
        isError={true}
        error={testError}
        hasSearched={true}
        fromCode="HWH"
        toCode="NDLS"
        onRetry={handleRetry}
      />
    );

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText(/Train Search Unavailable/i)).toBeInTheDocument();
    expect(screen.getByText(/Network timeout connecting to port 8080/i)).toBeInTheDocument();

    const retryBtn = screen.getByRole('button', { name: /Retry Search/i });
    fireEvent.click(retryBtn);
    expect(handleRetry).toHaveBeenCalledTimes(1);
  });

  it('renders empty results message when trains array is empty', () => {
    render(
      <TrainList
        trains={[]}
        isLoading={false}
        isError={false}
        hasSearched={true}
        fromCode="ABC"
        toCode="XYZ"
        onRetry={vi.fn()}
        onSwapAndSearch={vi.fn()}
      />
    );

    expect(screen.getByRole('heading', { name: /No direct trains found/i })).toBeInTheDocument();
    expect(screen.getByText(/There are no scheduled direct routes from/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Search reverse route \(XYZ → ABC\)/i })).toBeInTheDocument();
  });

  it('renders train list when results are provided', () => {
    render(
      <TrainList
        trains={MOCK_TRAINS}
        isLoading={false}
        isError={false}
        hasSearched={true}
        fromCode="HWH"
        toCode="NDLS"
        onRetry={vi.fn()}
      />
    );

    expect(screen.getByLabelText(/Found 2 direct trains from HWH to NDLS/i)).toBeInTheDocument();
    expect(screen.getByText('HOWRAH RAJDHANI')).toBeInTheDocument();
    expect(screen.getByText('KOLKATA RAJDHANI')).toBeInTheDocument();
  });
});

describe('TrainSearchPage Integration', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('reads URL query params, calls search API, and displays results', async () => {
    const searchTrainsSpy = vi.spyOn(api, 'searchTrains').mockResolvedValue(MOCK_TRAINS);

    const queryClient = createTestQueryClient();

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/search?from=HWH&to=NDLS']}>
          <TrainSearchPage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    // Initial check: API was invoked with params from URL
    expect(searchTrainsSpy).toHaveBeenCalledWith('HWH', 'NDLS');

    // Wait for results to render
    await waitFor(() => {
      expect(screen.getByText('HOWRAH RAJDHANI')).toBeInTheDocument();
    });

    expect(screen.getByText('12301')).toBeInTheDocument();
    expect(screen.getByLabelText(/Found 2 direct trains from HWH to NDLS/i)).toBeInTheDocument();
  });
});
