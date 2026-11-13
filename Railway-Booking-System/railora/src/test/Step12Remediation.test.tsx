import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { AuthProvider } from '@/features/auth/AuthContext';
import { SeatSelectionModal } from '@/features/booking/components/SeatSelectionModal';
import { TrainCard } from '@/features/search/components/TrainCard';
import { TrainList } from '@/features/search/components/TrainList';
import { bookingApi } from '@/features/booking/api';
import type { TrainSearchResponseDTO } from '@/features/search/types';
import type { SeatAvailabilityDTO, TravelClassAvailabilityDTO } from '@/features/booking/types';

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

vi.mock('@/features/auth/api', () => ({
  authApi: {
    getCurrentUser: vi.fn(),
    login: vi.fn(),
    refresh: vi.fn(),
    register: vi.fn(),
  },
}));

const mockedBookingApi = vi.mocked(bookingApi);

const mockTrain: TrainSearchResponseDTO = {
  trainId: 101,
  trainNumber: '12001',
  trainName: 'BHOPAL SHATABDI',
  fromStationCode: 'NDLS',
  fromStationName: 'NEW DELHI',
  fromArrivalTime: null,
  fromDepartureTime: '06:00:00',
  fromDayOffset: 0,
  toStationCode: 'BPL',
  toStationName: 'BHOPAL JN',
  toArrivalTime: '14:00:00',
  toDepartureTime: null,
  toDayOffset: 0,
};

describe('RAILORA STEP 12: Release Blocker Remediation Frontend Tests', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('Fix 2: Frontend Error Handling in SeatSelectionModal', () => {
    it('displays backend validation message when train does not operate on selected day', async () => {
      const backendError = new AxiosError(
        'Request failed with status code 400',
        'ERR_BAD_REQUEST',
        undefined,
        undefined,
        {
          data: {
            message: 'Train 12001 (BHOPAL SHATABDI) is not scheduled to operate on TUESDAY (2026-11-17)',
          },
          status: 400,
          statusText: 'Bad Request',
          headers: {},
          config: {} as InternalAxiosRequestConfig,
        },
      );

      mockedBookingApi.getJourneys.mockRejectedValueOnce(backendError);

      render(
        <MemoryRouter>
          <AuthProvider>
            <SeatSelectionModal
              isOpen={true}
              onClose={vi.fn()}
              train={mockTrain}
              travelDate="2026-11-17"
            />
          </AuthProvider>
        </MemoryRouter>,
      );

      // Verify the backend message is explicitly rendered in the UI
      await waitFor(() => {
        const matches = screen.getAllByText(
          /Train 12001 \(BHOPAL SHATABDI\) is not scheduled to operate on TUESDAY/i,
        );
        expect(matches.length).toBeGreaterThanOrEqual(1);
      });

      expect(screen.getByText(/Demo Booking Unavailable for this Train\/Date/i)).toBeInTheDocument();
    });

    it('falls back to safe generic error message when error has no response message', async () => {
      mockedBookingApi.getJourneys.mockRejectedValueOnce(new Error('Network connectivity issue'));

      render(
        <MemoryRouter>
          <AuthProvider>
            <SeatSelectionModal
              isOpen={true}
              onClose={vi.fn()}
              train={mockTrain}
              travelDate="2026-11-17"
            />
          </AuthProvider>
        </MemoryRouter>,
      );

      await waitFor(() => {
        const matches = screen.getAllByText('Network connectivity issue');
        expect(matches.length).toBeGreaterThanOrEqual(1);
      });
    });
  });

  describe('Fix 3: Timetable Disclosure in TrainCard', () => {
    it('displays "Scheduled Timetable · Railora Demo Engine" and no "Verified Scheduled Timetable"', () => {
      render(
        <MemoryRouter>
          <TrainCard train={mockTrain} travelDate="2026-12-01" />
        </MemoryRouter>,
      );

      expect(screen.getByText(/Scheduled Timetable · Railora Demo Engine/i)).toBeInTheDocument();
      expect(screen.queryByText(/Verified Scheduled Timetable/i)).not.toBeInTheDocument();
    });
  });

  describe('Fix 4: Search Results Disclosure in TrainList', () => {
    it('displays accurate disclaimer banners and avoids "Official scheduled timetables"', () => {
      render(
        <MemoryRouter>
          <TrainList
            trains={[mockTrain]}
            isLoading={false}
            isError={false}
            onRetry={vi.fn()}
            hasSearched={true}
            fromCode="NDLS"
            toCode="BPL"
            travelDate="2026-12-01"
          />
        </MemoryRouter>,
      );

      // Verify header meta disclosure
      expect(screen.getByText(/Imported Timetable Dataset · Railora Engine/i)).toBeInTheDocument();
      expect(screen.queryByText(/Showing official scheduled timetables/i)).not.toBeInTheDocument();

      // Verify route-level demo inventory banner
      expect(
        screen.getByText(/Railora Reservation Engine · Simulated Demo Inventory/i),
      ).toBeInTheDocument();
      expect(
        screen.getByText(/Not an official IRCTC availability confirmation/i),
      ).toBeInTheDocument();
    });
  });

  describe('Fix 5: Fare Disclosure in SeatSelectionModal', () => {
    it('labels synthetic dynamic fare calculations as Railora Demo Fare Quote with Estimated distance', async () => {
      const mockJourney = {
        journeyId: 42,
        trainId: 101,
        trainNumber: '12001',
        trainName: 'BHOPAL SHATABDI',
        fromStationId: 1,
        fromStationCode: 'NDLS',
        fromStationName: 'NEW DELHI',
        toStationId: 2,
        toStationCode: 'BPL',
        toStationName: 'BHOPAL JN',
        journeyDate: '2026-12-01',
        departureTime: '06:00:00',
        arrivalTime: '14:00:00',
        trainRouteId: 10,
        departureAt: '2026-12-01T06:00:00',
        arrivalAt: '2026-12-01T14:00:00',
      };

      const mockStops = [
        {
          journeyStopId: 101,
          stationId: 1,
          stationCode: 'NDLS',
          stationName: 'NEW DELHI',
          stopSequence: 1,
          arrivalAt: null,
          departureAt: '2026-12-01T06:00:00',
          distanceFromOriginKm: 0,
        },
        {
          journeyStopId: 102,
          stationId: 2,
          stationCode: 'BPL',
          stationName: 'BHOPAL JN',
          stopSequence: 2,
          arrivalAt: '2026-12-01T14:00:00',
          departureAt: null,
          distanceFromOriginKm: 700,
        },
      ];

      const mockSeats: SeatAvailabilityDTO[] = [
        {
          inventoryId: 501,
          journeyId: 42,
          coachId: 1,
          seatId: 501,
          seatNumber: 1,
          seatType: 'WINDOW',
          coachNumber: 'C1',
          coachType: 'CC',
          status: 'AVAILABLE' as const,
          holdExpiresAt: null,
          updatedAt: null,
          seatAllocationId: null,
          boardingJourneyStopId: 101,
          alightingJourneyStopId: 102,
        },
      ];

      const mockClasses: TravelClassAvailabilityDTO[] = [
        {
          classCode: 'CC',
          className: 'AC Chair Car',
          totalSeats: 16,
          availableSeats: 16,
          heldSeats: 0,
          bookedSeats: 0,
          baseFare: 650,
          coaches: [
            {
              coachId: 1,
              coachNumber: 'C1',
              coachType: 'CC',
              totalSeats: 16,
              availableSeats: 16,
              heldSeats: 0,
              bookedSeats: 0,
              seats: mockSeats,
            },
          ],
        },
      ];

      const mockFareQuote = {
        journeyId: 42,
        travelClass: 'CC',
        className: 'AC Chair Car',
        distanceKm: 700,
        distanceSource: 'ESTIMATED_RUN_DURATION',
        passengerCount: 1,
        perPassengerFare: {
          baseFare: 650,
          reservationCharge: 40,
          superfastCharge: 45,
          tax: 36.75,
          total: 771.75,
        },
        bookingTotal: 771.75,
        currency: 'INR',
        ratePerKm: 0.92,
      };

      mockedBookingApi.getJourneys.mockResolvedValueOnce([mockJourney]);
      mockedBookingApi.getJourneyStops.mockResolvedValueOnce(mockStops);
      mockedBookingApi.getJourneyClasses.mockResolvedValueOnce(mockClasses);
      mockedBookingApi.getJourneySeats.mockResolvedValueOnce(mockSeats);
      mockedBookingApi.getFareQuote.mockResolvedValue(mockFareQuote);

      render(
        <MemoryRouter>
          <AuthProvider>
            <SeatSelectionModal
              isOpen={true}
              onClose={vi.fn()}
              train={mockTrain}
              travelDate="2026-12-01"
            />
          </AuthProvider>
        </MemoryRouter>,
      );

      // Verify the fare card shows "Railora Demo Fare Quote (CC • 700 km · Estimated)"
      await waitFor(() => {
        expect(
          screen.getByText(/Railora Demo Fare Quote \(CC • 700 km · Estimated\):/i),
        ).toBeInTheDocument();
      });

      // Verify the demo disclosure notice
      expect(
        screen.getByText(/Simulated fare calculated by Railora Demo Engine for demonstration purposes/i),
      ).toBeInTheDocument();
    });
  });
});
