import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from '@/features/auth/AuthContext';
import { clearSession, setSession } from '@/features/auth/session';
import { bookingApi } from '@/features/booking/api';
import { authApi } from '@/features/auth/api';
import { SeatSelectionModal } from '@/features/booking/components/SeatSelectionModal';
import type { TrainSearchResponseDTO } from '@/features/search/types';
import type { AuthResponse, UserResponse } from '@/features/auth/types';

vi.mock('@/features/booking/api', () => ({
  bookingApi: {
    getJourneys: vi.fn(),
    getJourneyStops: vi.fn(),
    getJourneySeats: vi.fn(),
    holdSeat: vi.fn(),
    createBooking: vi.fn(),
    payBooking: vi.fn(),
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

const testTrain: TrainSearchResponseDTO = {
  trainId: 1,
  trainNumber: '12601',
  trainName: 'Mangalore Mail',
  fromStationCode: 'MAS',
  fromStationName: 'Chennai Central',
  fromArrivalTime: null,
  fromDepartureTime: '20:15:00',
  fromDayOffset: 0,
  toStationCode: 'SBC',
  toStationName: 'Bangalore City',
  toArrivalTime: '04:30:00',
  toDepartureTime: null,
  toDayOffset: 1,
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
});

describe('SeatSelectionModal component', () => {
  it('selects the journey run matching origin-to-destination direction and renders available seats', async () => {
    mockedBookingApi.getJourneys.mockResolvedValueOnce([
      {
        journeyId: 10,
        trainId: 1,
        trainNumber: '12601',
        trainName: 'Mangalore Mail',
        fromStationId: 1,
        fromStationCode: 'MAS',
        fromStationName: 'Chennai Central',
        toStationId: 2,
        toStationCode: 'SBC',
        toStationName: 'Bangalore City',
        journeyDate: '2026-10-27',
        departureTime: '20:15:00',
        arrivalTime: '04:30:00',
        trainRouteId: 1,
        departureAt: '2026-10-27T20:15:00',
        arrivalAt: '2026-10-28T04:30:00',
      },
    ]);

    mockedBookingApi.getJourneyStops.mockResolvedValueOnce([
      {
        journeyStopId: 101,
        stationId: 1,
        stationCode: 'MAS',
        stationName: 'Chennai Central',
        stopSequence: 1,
        arrivalAt: null,
        departureAt: '2026-10-27T20:15:00',
      },
      {
        journeyStopId: 102,
        stationId: 2,
        stationCode: 'SBC',
        stationName: 'Bangalore City',
        stopSequence: 2,
        arrivalAt: '2026-10-28T04:30:00',
        departureAt: null,
      },
    ]);

    mockedBookingApi.getJourneySeats.mockResolvedValueOnce([
      {
        inventoryId: 1,
        journeyId: 10,
        coachId: 1,
        coachNumber: 'B1',
        seatId: 501,
        seatNumber: 12,
        seatType: 'LOWER',
        status: 'AVAILABLE',
        holdExpiresAt: null,
        updatedAt: null,
        seatAllocationId: null,
        boardingJourneyStopId: 101,
        alightingJourneyStopId: 102,
      },
      {
        inventoryId: 2,
        journeyId: 10,
        coachId: 1,
        coachNumber: 'B1',
        seatId: 502,
        seatNumber: 14,
        seatType: 'UPPER',
        status: 'BOOKED',
        holdExpiresAt: null,
        updatedAt: null,
        seatAllocationId: 99,
        boardingJourneyStopId: 101,
        alightingJourneyStopId: 102,
      },
    ]);

    render(
      <MemoryRouter>
        <AuthProvider>
          <SeatSelectionModal
            isOpen={true}
            onClose={vi.fn()}
            train={testTrain}
            travelDate="2026-10-27"
          />
        </AuthProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByRole('heading', { name: /select your seat/i })).toBeInTheDocument();
    expect(screen.getByText('#12')).toBeInTheDocument();
    expect(screen.getByText('#14')).toBeInTheDocument();

    // Seat #14 is BOOKED, so its button should be disabled
    const bookedSeat = screen.getByText('#14').closest('button');
    expect(bookedSeat).toBeDisabled();

    // Seat #12 is AVAILABLE, so its button should be enabled
    const availableSeat = screen.getByText('#12').closest('button');
    expect(availableSeat).toBeEnabled();
  });

  it('completes the entire hold -> create booking -> mock pay -> confirmation lifecycle', async () => {
    setSession(testTokens);

    mockedBookingApi.getJourneys.mockResolvedValueOnce([
      {
        journeyId: 10,
        trainId: 1,
        trainNumber: '12601',
        trainName: 'Mangalore Mail',
        fromStationId: 1,
        fromStationCode: 'MAS',
        fromStationName: 'Chennai Central',
        toStationId: 2,
        toStationCode: 'SBC',
        toStationName: 'Bangalore City',
        journeyDate: '2026-10-27',
        departureTime: '20:15:00',
        arrivalTime: '04:30:00',
        trainRouteId: 1,
        departureAt: '2026-10-27T20:15:00',
        arrivalAt: '2026-10-28T04:30:00',
      },
    ]);

    mockedBookingApi.getJourneyStops.mockResolvedValueOnce([
      {
        journeyStopId: 101,
        stationId: 1,
        stationCode: 'MAS',
        stationName: 'Chennai Central',
        stopSequence: 1,
        arrivalAt: null,
        departureAt: '2026-10-27T20:15:00',
      },
      {
        journeyStopId: 102,
        stationId: 2,
        stationCode: 'SBC',
        stationName: 'Bangalore City',
        stopSequence: 2,
        arrivalAt: '2026-10-28T04:30:00',
        departureAt: null,
      },
    ]);

    mockedBookingApi.getJourneySeats.mockResolvedValueOnce([
      {
        inventoryId: 1,
        journeyId: 10,
        coachId: 1,
        coachNumber: 'B1',
        seatId: 501,
        seatNumber: 12,
        seatType: 'LOWER',
        status: 'AVAILABLE',
        holdExpiresAt: null,
        updatedAt: null,
        seatAllocationId: null,
        boardingJourneyStopId: 101,
        alightingJourneyStopId: 102,
      },
    ]);

    mockedBookingApi.holdSeat.mockResolvedValueOnce({
      inventoryId: 1,
      journeyId: 10,
      coachId: 1,
      coachNumber: 'B1',
      seatId: 501,
      seatNumber: 12,
      seatType: 'LOWER',
      status: 'HOLD',
      holdExpiresAt: '2026-10-27T20:25:00',
      updatedAt: '2026-10-27T20:15:00',
      seatAllocationId: 700,
      boardingJourneyStopId: 101,
      alightingJourneyStopId: 102,
    });

    mockedBookingApi.createBooking.mockResolvedValueOnce({
      bookingId: 88,
      bookingRef: 'BKG-20261027-MAS01',
      journeyId: 10,
      seatId: 501,
      coachNumber: 'B1',
      seatNumber: 12,
      bookingStatus: 'PENDING_PAYMENT',
      paymentStatus: 'PENDING',
      amount: 500.0,
      createdAt: '2026-10-27T20:15:00',
    });

    mockedBookingApi.payBooking.mockResolvedValueOnce({
      transactionRef: 'TXN-SUCCESS-99',
      bookingRef: 'BKG-20261027-MAS01',
      bookingId: 88,
      amount: 500.0,
      paymentStatus: 'SUCCESS',
      bookingStatus: 'CONFIRMED',
      paymentMethod: 'MOCK_UPI',
      message: 'Success',
      transactionTime: '2026-10-27T20:16:00',
    });

    render(
      <MemoryRouter>
        <AuthProvider>
          <SeatSelectionModal
            isOpen={true}
            onClose={vi.fn()}
            train={testTrain}
            travelDate="2026-10-27"
          />
        </AuthProvider>
      </MemoryRouter>,
    );

    // 1. Select seat #12
    const seatBtn = await screen.findByText('#12');
    fireEvent.click(seatBtn);

    // 2. Click Hold & Proceed
    const proceedBtn = screen.getByRole('button', { name: /hold & proceed/i });
    fireEvent.click(proceedBtn);

    // 3. Reaches payment step
    expect(await screen.findByRole('heading', { name: /confirm & pay/i })).toBeInTheDocument();
    expect(screen.getByText('BKG-20261027-MAS01')).toBeInTheDocument();

    // 4. Pay & Confirm
    const payBtn = screen.getByRole('button', { name: /pay ₹500.00 & confirm/i });
    fireEvent.click(payBtn);

    // 5. Reaches confirmed step
    expect(await screen.findByRole('heading', { name: /booking confirmed!/i })).toBeInTheDocument();
    expect(screen.getByText('TXN-SUCCESS-99')).toBeInTheDocument();
    expect(mockedBookingApi.payBooking).toHaveBeenCalledWith(
      'BKG-20261027-MAS01',
      expect.objectContaining({
        paymentMethod: 'MOCK_UPI',
        idempotencyKey: expect.stringMatching(/^PAY-/),
        simulateFailure: false,
      }),
    );
  });

  it('handles simulated payment failure and enables retry', async () => {
    setSession(testTokens);

    mockedBookingApi.getJourneys.mockResolvedValueOnce([
      {
        journeyId: 10,
        trainId: 1,
        trainNumber: '12601',
        trainName: 'Mangalore Mail',
        fromStationId: 1,
        fromStationCode: 'MAS',
        fromStationName: 'Chennai Central',
        toStationId: 2,
        toStationCode: 'SBC',
        toStationName: 'Bangalore City',
        journeyDate: '2026-10-27',
        departureTime: '20:15:00',
        arrivalTime: '04:30:00',
        trainRouteId: 1,
        departureAt: '2026-10-27T20:15:00',
        arrivalAt: '2026-10-28T04:30:00',
      },
    ]);

    mockedBookingApi.getJourneyStops.mockResolvedValueOnce([
      {
        journeyStopId: 101,
        stationId: 1,
        stationCode: 'MAS',
        stationName: 'Chennai Central',
        stopSequence: 1,
        arrivalAt: null,
        departureAt: '2026-10-27T20:15:00',
      },
      {
        journeyStopId: 102,
        stationId: 2,
        stationCode: 'SBC',
        stationName: 'Bangalore City',
        stopSequence: 2,
        arrivalAt: '2026-10-28T04:30:00',
        departureAt: null,
      },
    ]);

    mockedBookingApi.getJourneySeats.mockResolvedValueOnce([
      {
        inventoryId: 1,
        journeyId: 10,
        coachId: 1,
        coachNumber: 'B1',
        seatId: 501,
        seatNumber: 12,
        seatType: 'LOWER',
        status: 'AVAILABLE',
        holdExpiresAt: null,
        updatedAt: null,
        seatAllocationId: null,
        boardingJourneyStopId: 101,
        alightingJourneyStopId: 102,
      },
    ]);

    mockedBookingApi.holdSeat.mockResolvedValueOnce({
      inventoryId: 1,
      journeyId: 10,
      coachId: 1,
      coachNumber: 'B1',
      seatId: 501,
      seatNumber: 12,
      seatType: 'LOWER',
      status: 'HOLD',
      holdExpiresAt: '2026-10-27T20:25:00',
      updatedAt: '2026-10-27T20:15:00',
      seatAllocationId: 700,
      boardingJourneyStopId: 101,
      alightingJourneyStopId: 102,
    });

    mockedBookingApi.createBooking.mockResolvedValueOnce({
      bookingId: 88,
      bookingRef: 'BKG-20261027-MAS01',
      journeyId: 10,
      seatId: 501,
      coachNumber: 'B1',
      seatNumber: 12,
      bookingStatus: 'PENDING_PAYMENT',
      paymentStatus: 'PENDING',
      amount: 500.0,
      createdAt: '2026-10-27T20:15:00',
    });

    // Mock payment failure response (HTTP 200 with FAILED status)
    mockedBookingApi.payBooking.mockResolvedValueOnce({
      transactionRef: 'TXN-FAIL-01',
      bookingRef: 'BKG-20261027-MAS01',
      bookingId: 88,
      amount: 500.0,
      paymentStatus: 'FAILED',
      bookingStatus: 'PENDING_PAYMENT',
      paymentMethod: 'MOCK_UPI',
      message: 'Payment failed: Simulated payment failure',
      transactionTime: '2026-10-27T20:16:00',
    });
    mockedBookingApi.payBooking.mockResolvedValueOnce({
      transactionRef: 'TXN-RETRY-02',
      bookingRef: 'BKG-20261027-MAS01',
      bookingId: 88,
      amount: 500.0,
      paymentStatus: 'SUCCESS',
      bookingStatus: 'CONFIRMED',
      paymentMethod: 'MOCK_UPI',
      message: 'Success',
      transactionTime: '2026-10-27T20:17:00',
    });

    render(
      <MemoryRouter>
        <AuthProvider>
          <SeatSelectionModal
            isOpen={true}
            onClose={vi.fn()}
            train={testTrain}
            travelDate="2026-10-27"
          />
        </AuthProvider>
      </MemoryRouter>,
    );

    // Select seat and proceed
    const seatBtn = await screen.findByText('#12');
    fireEvent.click(seatBtn);
    fireEvent.click(screen.getByRole('button', { name: /hold & proceed/i }));

    // Wait for payment step
    await screen.findByRole('heading', { name: /confirm & pay/i });

    // Enable simulated failure checkbox
    const checkbox = screen.getByLabelText(/simulate gateway payment failure/i);
    fireEvent.click(checkbox);

    // Click pay
    fireEvent.click(screen.getByRole('button', { name: /pay ₹500.00 & confirm/i }));

    // Verify error is displayed and step remains on payment (does NOT go to confirmed)
    expect(await screen.findByText(/payment failed: simulated payment failure/i)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /booking confirmed!/i })).not.toBeInTheDocument();

    // Verify Retry Payment button appears
    expect(screen.getByRole('button', { name: /retry payment/i })).toBeInTheDocument();

    const failedAttempt = mockedBookingApi.payBooking.mock.calls[0][1];
    fireEvent.click(checkbox);
    fireEvent.click(screen.getByRole('button', { name: /retry payment/i }));
    expect(await screen.findByRole('heading', { name: /booking confirmed!/i })).toBeInTheDocument();
    const successfulRetry = mockedBookingApi.payBooking.mock.calls[1][1];
    expect(successfulRetry?.idempotencyKey).not.toBe(failedAttempt?.idempotencyKey);
    expect(successfulRetry?.simulateFailure).toBe(false);
  });

  it('passes admissionToken and TATKAL bookingMode to holdSeat and createBooking for Tatkal bookings, and never stores admissionToken in storage', async () => {
    setSession(testTokens);

    const testAdmissionToken = 'tatkal-admit-pass-secure-token-99';

    mockedBookingApi.getJourneys.mockResolvedValueOnce([
      {
        journeyId: 10,
        trainId: 1,
        trainNumber: '12601',
        trainName: 'Mangalore Mail',
        fromStationId: 1,
        fromStationCode: 'MAS',
        fromStationName: 'Chennai Central',
        toStationId: 2,
        toStationCode: 'SBC',
        toStationName: 'Bangalore City',
        journeyDate: '2026-10-27',
        departureTime: '20:15:00',
        arrivalTime: '04:30:00',
        trainRouteId: 1,
        departureAt: '2026-10-27T20:15:00',
        arrivalAt: '2026-10-28T04:30:00',
      },
    ]);

    mockedBookingApi.getJourneyStops.mockResolvedValueOnce([
      {
        journeyStopId: 101,
        stationId: 1,
        stationCode: 'MAS',
        stationName: 'Chennai Central',
        stopSequence: 1,
        arrivalAt: null,
        departureAt: '2026-10-27T20:15:00',
      },
      {
        journeyStopId: 102,
        stationId: 2,
        stationCode: 'SBC',
        stationName: 'Bangalore City',
        stopSequence: 2,
        arrivalAt: '2026-10-28T04:30:00',
        departureAt: null,
      },
    ]);

    mockedBookingApi.getJourneySeats.mockResolvedValueOnce([
      {
        inventoryId: 1,
        journeyId: 10,
        coachId: 1,
        coachNumber: 'B1',
        seatId: 501,
        seatNumber: 12,
        seatType: 'LOWER',
        status: 'AVAILABLE',
        holdExpiresAt: null,
        updatedAt: null,
        seatAllocationId: null,
        boardingJourneyStopId: 101,
        alightingJourneyStopId: 102,
      },
    ]);

    mockedBookingApi.holdSeat.mockResolvedValueOnce({
      inventoryId: 1,
      journeyId: 10,
      coachId: 1,
      coachNumber: 'B1',
      seatId: 501,
      seatNumber: 12,
      seatType: 'LOWER',
      status: 'HOLD',
      holdExpiresAt: '2026-10-27T20:25:00',
      updatedAt: '2026-10-27T20:15:00',
      seatAllocationId: 700,
      boardingJourneyStopId: 101,
      alightingJourneyStopId: 102,
    });

    mockedBookingApi.createBooking.mockResolvedValueOnce({
      bookingId: 88,
      bookingRef: 'BKG-20261027-MAS01',
      journeyId: 10,
      seatId: 501,
      coachNumber: 'B1',
      seatNumber: 12,
      bookingStatus: 'PENDING_PAYMENT',
      paymentStatus: 'PENDING',
      amount: 500.0,
      createdAt: '2026-10-27T20:15:00',
    });

    render(
      <MemoryRouter>
        <AuthProvider>
          <SeatSelectionModal
            isOpen={true}
            onClose={vi.fn()}
            train={testTrain}
            travelDate="2026-10-27"
            admissionToken={testAdmissionToken}
            quota="TATKAL"
          />
        </AuthProvider>
      </MemoryRouter>,
    );

    // Verify Tatkal Admitted badge is displayed
    expect(await screen.findByText('Tatkal Admitted')).toBeInTheDocument();

    // Select seat and proceed
    const seatBtn = await screen.findByText('#12');
    fireEvent.click(seatBtn);

    const proceedBtn = screen.getByRole('button', { name: /hold & proceed/i });
    fireEvent.click(proceedBtn);

    expect(await screen.findByRole('heading', { name: /confirm & pay/i })).toBeInTheDocument();

    // Verify holdSeat was called with TATKAL bookingMode and the admissionToken
    expect(mockedBookingApi.holdSeat).toHaveBeenCalledWith(
      10,
      {
        seatId: 501,
        holdMinutes: 10,
        boardingJourneyStopId: 101,
        alightingJourneyStopId: 102,
        bookingMode: 'TATKAL',
      },
      testAdmissionToken,
    );

    // Verify createBooking was called with TATKAL bookingMode
    expect(mockedBookingApi.createBooking).toHaveBeenCalledWith({
      journeyId: 10,
      seatId: 501,
      boardingJourneyStopId: 101,
      alightingJourneyStopId: 102,
      bookingMode: 'TATKAL',
    });

    // Verify admissionToken is NEVER stored in localStorage or sessionStorage
    expect(localStorage.getItem('admissionToken')).toBeNull();
    expect(localStorage.getItem('admissionPass')).toBeNull();
    expect(sessionStorage.getItem('admissionToken')).toBeNull();
    expect(sessionStorage.getItem('admissionPass')).toBeNull();
  });
});
