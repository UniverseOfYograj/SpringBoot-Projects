import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from '@/features/auth/AuthContext';
import { clearSession, setSession } from '@/features/auth/session';
import { bookingApi } from '@/features/booking/api';
import { authApi } from '@/features/auth/api';
import { TrainCard } from '@/features/search/components/TrainCard';
import { TrainSearchForm } from '@/features/search/components/TrainSearchForm';
import { SeatSelectionModal } from '@/features/booking/components/SeatSelectionModal';
import { useOperationalJourneys } from '@/features/search/hooks/useOperationalJourneys';
import type { TrainSearchResponseDTO } from '@/features/search/types';
import type { JourneyDTO } from '@/features/booking/types';
import type { UserResponse, AuthResponse } from '@/features/auth/types';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook } from '@testing-library/react';

vi.mock('@/features/booking/api', () => ({
  bookingApi: {
    getJourneys: vi.fn(),
    getJourneyStops: vi.fn(),
    getJourneySeats: vi.fn(),
    getJourneyClasses: vi.fn(),
    holdSeat: vi.fn(),
    holdSeats: vi.fn(),
    createBooking: vi.fn(),
    payBooking: vi.fn(),
    getSimulatedPaymentSummary: vi.fn(),
    simulatePaymentSuccess: vi.fn(),
    getFareQuote: vi.fn(),
  },
}));

vi.mock('@/features/auth/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/auth/api')>();
  return {
    ...actual,
    authApi: {
      getCurrentUser: vi.fn(),
      login: vi.fn(),
      refresh: vi.fn(),
      register: vi.fn(),
    },
  };
});

const mockedBookingApi = vi.mocked(bookingApi);
const mockedAuthApi = vi.mocked(authApi);

const sampleTrain12301: TrainSearchResponseDTO = {
  trainId: 101,
  trainNumber: '12301',
  trainName: 'Howrah Rajdhani Express',
  fromStationCode: 'HWH',
  fromStationName: 'Howrah Junction',
  fromArrivalTime: null,
  fromDepartureTime: '16:55:00',
  fromDayOffset: 0,
  toStationCode: 'NDLS',
  toStationName: 'New Delhi',
  toArrivalTime: '10:05:00',
  toDepartureTime: null,
  toDayOffset: 1,
};

const sampleTimetableTrain12273: TrainSearchResponseDTO = {
  trainId: 102,
  trainNumber: '12273',
  trainName: 'Howrah Duronto Express',
  fromStationCode: 'HWH',
  fromStationName: 'Howrah Junction',
  fromArrivalTime: null,
  fromDepartureTime: '12:50:00',
  fromDayOffset: 0,
  toStationCode: 'NDLS',
  toStationName: 'New Delhi',
  toArrivalTime: '06:25:00',
  toDepartureTime: null,
  toDayOffset: 1,
};

const demoJourney1: JourneyDTO = {
  journeyId: 1,
  trainId: 101,
  trainNumber: '12301',
  trainName: 'Howrah Rajdhani Express',
  fromStationId: 8501,
  fromStationCode: 'HWH',
  fromStationName: 'Howrah Junction',
  toStationId: 4,
  toStationCode: 'NDLS',
  toStationName: 'New Delhi',
  journeyDate: '2026-12-01',
  departureTime: '16:55:00',
  arrivalTime: '10:05:00',
  trainRouteId: 421,
  departureAt: '2026-12-01T16:55:00',
  arrivalAt: '2026-12-02T10:05:00',
};

const testUser: UserResponse = {
  userId: 1,
  username: 'passenger',
  fullName: 'Arun Kumar',
  email: 'arun@example.com',
  phoneNumber: '9876543210',
  status: 'ACTIVE',
  createdAt: '2026-10-26T00:00:00',
};

const testTokens: AuthResponse = {
  accessToken: 'valid-token',
  refreshToken: 'refresh-token',
  tokenType: 'Bearer',
  expiresIn: 3600,
  message: 'Success',
};

beforeEach(() => {
  clearSession();
  sessionStorage.clear();
  vi.clearAllMocks();
  mockedAuthApi.getCurrentUser.mockResolvedValue(testUser);
});

afterEach(() => {
  clearSession();
  sessionStorage.clear();
  vi.clearAllMocks();
});

describe('Phase 17.24B — Complete Booking Experience Remediation', () => {
  describe('useOperationalJourneys hook', () => {
    it('resolves operational journeys and maps by trainId and trainNumber', async () => {
      mockedBookingApi.getJourneys.mockResolvedValueOnce([demoJourney1]);

      const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false } },
      });

      const wrapper = ({ children }: { children: React.ReactNode }) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      );

      const { result } = renderHook(() => useOperationalJourneys('2026-12-01'), { wrapper });

      await waitFor(() => {
        expect(result.current.journeys).toHaveLength(1);
      });

      expect(result.current.findJourney(101)).toEqual(demoJourney1);
      expect(result.current.findJourney(999, '12301')).toEqual(demoJourney1);
      expect(result.current.findJourney(999, '12273')).toBeUndefined();
    });

    it('works safely when rendered outside QueryClientProvider without throwing', () => {
      const { result } = renderHook(() => useOperationalJourneys('2026-12-01'));
      expect(result.current.journeys).toEqual([]);
      expect(result.current.hasQueryClient).toBe(false);
      expect(result.current.findJourney(101)).toBeUndefined();
    });
  });

  describe('TrainCard timetable vs operational status', () => {
    it('disables Book Seats and Tatkal for timetable-only trains and displays informative badge and warning', () => {
      render(
        <MemoryRouter>
          <TrainCard
            train={sampleTimetableTrain12273}
            travelDate="2026-11-12"
            isTimetableOnly={true}
            journey={null}
          />
        </MemoryRouter>,
      );

      // Verify "Timetable Only" badge is displayed
      expect(screen.getByText('Timetable Only')).toBeInTheDocument();

      // Verify informative message is displayed
      expect(
        screen.getByText('Demo booking is not available for this train/date.'),
      ).toBeInTheDocument();

      // Verify Book Seats button is disabled with aria-label
      const bookBtn = screen.getByRole('button', {
        name: new RegExp(`demo booking is not available for ${sampleTimetableTrain12273.trainNumber}`, 'i'),
      });
      expect(bookBtn).toBeDisabled();

      // Verify Tatkal Queue entry is disabled/non-clickable
      const tatkalEntry = screen.getByText('Tatkal Queue');
      expect(tatkalEntry.closest('[aria-disabled="true"]')).toBeInTheDocument();
    });

    it('enables Book Seats and Tatkal for operational journey with "Demo Booking Ready" badge', async () => {
      render(
        <MemoryRouter>
          <TrainCard
            train={sampleTrain12301}
            travelDate="2026-12-01"
            isTimetableOnly={false}
            journey={demoJourney1}
          />
        </MemoryRouter>,
      );

      // Verify "Demo Booking Ready" badge is displayed
      expect(screen.getByText('Demo Booking Ready')).toBeInTheDocument();

      // Verify Book Seats button is enabled
      const bookBtn = screen.getByRole('button', {
        name: new RegExp(`select seats for ${sampleTrain12301.trainNumber}`, 'i'),
      });
      expect(bookBtn).toBeEnabled();

      // Verify Tatkal Queue link is enabled and active
      const tatkalLink = screen.getByRole('link', {
        name: new RegExp(`enter tatkal waiting room for ${sampleTrain12301.trainNumber}`, 'i'),
      });
      expect(tatkalLink).toBeInTheDocument();

      // Clicking book seats opens the seat selection modal
      fireEvent.click(bookBtn);
      expect(await screen.findByRole('dialog')).toBeInTheDocument();
      expect(screen.getByRole('heading', { name: /select your seats/i })).toBeInTheDocument();
    });
  });

  describe('TrainSearchForm Demo Route Shortcut', () => {
    it('populates HWH -> NDLS and 2026-12-01 when the demo shortcut pill is clicked', () => {
      const onSearchMock = vi.fn();

      render(<TrainSearchForm onSearch={onSearchMock} />);

      // Find the demo shortcut card with route information
      expect(
        screen.getByText(/Train 12301 \| HWH → NDLS \| 1 December 2026/i),
      ).toBeInTheDocument();

      // Click "Try Demo Booking"
      const demoBtn = screen.getByRole('button', {
        name: /try demo booking/i,
      });
      expect(demoBtn).toBeInTheDocument();

      fireEvent.click(demoBtn);

      // Verify search was invoked with exact demo parameters
      expect(onSearchMock).toHaveBeenCalledWith({
        from: 'HWH',
        to: 'NDLS',
        date: '2026-12-01',
      });
    });
  });

  describe('SeatSelectionModal empty state, stepper and print ticket', () => {
    it('renders dedicated empty state view with demo guidance when train has no operational journey', async () => {
      mockedBookingApi.getJourneys.mockResolvedValueOnce([]);

      render(
        <MemoryRouter>
          <AuthProvider>
            <SeatSelectionModal
              isOpen={true}
              onClose={vi.fn()}
              train={sampleTimetableTrain12273}
              travelDate="2026-11-12"
            />
          </AuthProvider>
        </MemoryRouter>,
      );

      // Verify dedicated empty state heading
      expect(
        await screen.findByText('Demo Booking Unavailable for this Train/Date'),
      ).toBeInTheDocument();

      // Verify demo route guidance
      expect(screen.getByText(/Configured Production Demo Run/i)).toBeInTheDocument();
      expect(screen.getByText(/Train 12301/i)).toBeInTheDocument();
      expect(screen.getByText(/1 December 2026/i)).toBeInTheDocument();

      // Verify Close buttons are present (header and empty state action)
      expect(screen.getAllByRole('button', { name: /close/i }).length).toBeGreaterThanOrEqual(1);
    });

    it('renders visual step indicator in modal header and allows print ticket in confirmed state', async () => {
      setSession(testTokens);

      mockedBookingApi.getJourneys.mockResolvedValueOnce([demoJourney1]);
      mockedBookingApi.getJourneyStops.mockResolvedValueOnce([
        {
          journeyStopId: 10,
          stationId: 8501,
          stationCode: 'HWH',
          stationName: 'Howrah Junction',
          stopSequence: 1,
          arrivalAt: null,
          departureAt: '2026-12-01T16:55:00',
        },
        {
          journeyStopId: 227,
          stationId: 4,
          stationCode: 'NDLS',
          stationName: 'New Delhi',
          stopSequence: 2,
          arrivalAt: '2026-12-02T10:05:00',
          departureAt: null,
        },
      ]);

      mockedBookingApi.getJourneySeats.mockResolvedValueOnce([
        {
          inventoryId: 1,
          journeyId: 1,
          coachId: 1,
          coachNumber: 'A1',
          coachType: '2A',
          seatId: 101,
          seatNumber: 1,
          seatType: 'LOWER',
          status: 'AVAILABLE',
          holdExpiresAt: null,
          updatedAt: null,
          seatAllocationId: null,
          boardingJourneyStopId: 10,
          alightingJourneyStopId: 227,
        },
      ]);

      mockedBookingApi.holdSeat.mockResolvedValueOnce({
        inventoryId: 1,
        journeyId: 1,
        coachId: 1,
        coachNumber: 'A1',
        coachType: '2A',
        seatId: 101,
        seatNumber: 1,
        seatType: 'LOWER',
        status: 'HOLD',
        holdExpiresAt: '2026-12-01T17:05:00',
        updatedAt: '2026-12-01T16:55:00',
        seatAllocationId: 701,
        boardingJourneyStopId: 10,
        alightingJourneyStopId: 227,
      });

      mockedBookingApi.createBooking.mockResolvedValueOnce({
        bookingId: 201,
        bookingRef: 'BKG-20261201-DEMO01',
        pnr: '2345678901',
        journeyId: 1,
        seatId: 101,
        coachNumber: 'A1',
        seatNumber: 1,
        bookingStatus: 'PENDING_PAYMENT',
        paymentStatus: 'PENDING',
        amount: 1500.0,
        createdAt: '2026-12-01T16:55:00',
      });

      mockedBookingApi.payBooking.mockResolvedValueOnce({
        transactionRef: 'TXN-DEMO-CONFIRMED',
        bookingRef: 'BKG-20261201-DEMO01',
        bookingId: 201,
        amount: 1500.0,
        paymentStatus: 'SUCCESS',
        bookingStatus: 'CONFIRMED',
        paymentMethod: 'MOCK_UPI',
        message: 'Success',
        transactionTime: '2026-12-01T17:00:00',
      });

      // Spy on window.print
      const printSpy = vi.spyOn(window, 'print').mockImplementation(() => {});

      render(
        <MemoryRouter>
          <AuthProvider>
            <SeatSelectionModal
              isOpen={true}
              onClose={vi.fn()}
              train={sampleTrain12301}
              travelDate="2026-12-01"
            />
          </AuthProvider>
        </MemoryRouter>,
      );

      // Verify Step 1 Indicator
      expect(await screen.findByText('Class & Berths')).toBeInTheDocument();
      expect(screen.getAllByText('Passenger Details').length).toBeGreaterThanOrEqual(1);
      expect(screen.getByText('Review & Pay')).toBeInTheDocument();

      // Select seat #1 and proceed
      const seatBtn = await screen.findByText('#1');
      fireEvent.click(seatBtn);

      const holdBtn = screen.getByRole('button', { name: /hold & proceed/i });
      fireEvent.click(holdBtn);

      // Reaches Review & Pay step
      expect(await screen.findByRole('heading', { name: /confirm & pay/i })).toBeInTheDocument();
      const payBtn = screen.getByRole('button', { name: /pay ₹1500\.00 & confirm/i });
      fireEvent.click(payBtn);

      // Reaches Confirmed step with 10-digit PNR
      expect(await screen.findByRole('heading', { name: /booking confirmed!/i })).toBeInTheDocument();
      expect(screen.getByText('2345678901')).toBeInTheDocument();

      // Verify "Print Ticket" button is present and triggers window.print
      const printBtn = screen.getByRole('button', { name: /print ticket/i });
      expect(printBtn).toBeInTheDocument();
      fireEvent.click(printBtn);
      expect(printSpy).toHaveBeenCalledTimes(1);

      printSpy.mockRestore();
    });
  });
});
