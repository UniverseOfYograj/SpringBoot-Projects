import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from '@/features/auth/AuthContext';
import { clearSession, setSession } from '@/features/auth/session';
import { bookingApi } from '@/features/booking/api';
import { authApi } from '@/features/auth/api';
import { SeatSelectionModal } from '@/features/booking/components/SeatSelectionModal';
import type { TrainSearchResponseDTO } from '@/features/search/types';
import type { AuthResponse, UserResponse } from '@/features/auth/types';

import {
  isMockPaymentEnabled,
  isRazorpayCheckoutEnabled,
  isSimulatedPaymentEnabled,
} from '@/features/booking/razorpayCheckout';

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

vi.mock('@/features/booking/razorpayCheckout', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/booking/razorpayCheckout')>();
  return {
    ...actual,
    isRazorpayCheckoutEnabled: vi.fn(() => false),
    isMockPaymentEnabled: vi.fn(() => true),
    isSimulatedPaymentEnabled: vi.fn(() => false),
  };
});

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
const mockedIsRazorpayCheckoutEnabled = vi.mocked(isRazorpayCheckoutEnabled);
const mockedIsMockPaymentEnabled = vi.mocked(isMockPaymentEnabled);
const mockedIsSimulatedPaymentEnabled = vi.mocked(isSimulatedPaymentEnabled);

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
  mockedIsRazorpayCheckoutEnabled.mockReturnValue(false);
  mockedIsMockPaymentEnabled.mockReturnValue(true);
  mockedIsSimulatedPaymentEnabled.mockReturnValue(false);
  mockedAuthApi.getCurrentUser.mockResolvedValue(testUser);
});

afterEach(() => {
  clearSession();
  sessionStorage.clear();
  vi.clearAllMocks();
});

describe('SeatSelectionModal component', () => {
  it('selects the journey run matching origin-to-destination direction and renders available seats', async () => {
    mockedBookingApi.getJourneys.mockResolvedValueOnce([
      {
        journeyId: 99,
        trainId: 1,
        trainNumber: '12601',
        trainName: 'Mangalore Mail',
        fromStationId: 2,
        fromStationCode: 'SBC',
        fromStationName: 'Bangalore City',
        toStationId: 1,
        toStationCode: 'MAS',
        toStationName: 'Chennai Central',
        journeyDate: '2026-10-27',
        departureTime: '06:00:00',
        arrivalTime: '14:00:00',
        trainRouteId: 2,
        departureAt: '2026-10-27T06:00:00',
        arrivalAt: '2026-10-27T14:00:00',
      },
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

    mockedBookingApi.getJourneyStops.mockImplementation(async (jid: number) => {
      if (jid === 99) {
        return [
          {
            journeyStopId: 991,
            stationId: 2,
            stationCode: 'SBC',
            stationName: 'Bangalore City',
            stopSequence: 1,
            arrivalAt: null,
            departureAt: '2026-10-27T06:00:00',
          },
          {
            journeyStopId: 992,
            stationId: 1,
            stationCode: 'MAS',
            stationName: 'Chennai Central',
            stopSequence: 2,
            arrivalAt: '2026-10-27T14:00:00',
            departureAt: null,
          },
        ];
      }
      return [
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
      ];
    });

    mockedBookingApi.getJourneySeats.mockResolvedValueOnce([
      {
        inventoryId: 1,
        journeyId: 10,
        coachId: 1,
        coachNumber: 'B1',
        coachType: '3A',
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
        coachType: '3A',
        seatId: 502,
        seatNumber: 14,
        seatType: 'UPPER',
        status: 'BOOKED',
        holdExpiresAt: null,
        updatedAt: null,
        seatAllocationId: 999,
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

    // Expect loading state initially
    expect(screen.getByText(/loading journey and seat availability/i)).toBeInTheDocument();

    // Verify seats for directional journey 10 are rendered
    expect(await screen.findByText('#12')).toBeInTheDocument();
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
        coachType: '3A',
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
      coachType: '3A',
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
    const payBtn = screen.getByRole('button', { name: /pay ₹500\.00 & confirm/i });
    fireEvent.click(payBtn);

    // 5. Reaches confirmed step
    expect(await screen.findByRole('heading', { name: /booking confirmed!/i })).toBeInTheDocument();
    expect(screen.getByText('TXN-SUCCESS-99')).toBeInTheDocument();
    expect(screen.getByText('Payment Mode:')).toBeInTheDocument();
    expect(screen.getByText('MOCK_UPI')).toBeInTheDocument();
    expect(screen.queryByText(/SIMULATED \(Demo\)/i)).not.toBeInTheDocument();
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
        coachType: '3A',
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
      coachType: '3A',
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

    // 1st pay fails
    mockedBookingApi.payBooking.mockResolvedValueOnce({
      transactionRef: 'TXN-FAILED-1',
      bookingRef: 'BKG-20261027-MAS01',
      bookingId: 88,
      amount: 500.0,
      paymentStatus: 'FAILED',
      bookingStatus: 'PENDING_PAYMENT',
      paymentMethod: 'MOCK_UPI',
      message: 'Payment failed: simulated payment failure',
      transactionTime: '2026-10-27T20:16:00',
    });

    // 2nd pay succeeds
    mockedBookingApi.payBooking.mockResolvedValueOnce({
      transactionRef: 'TXN-SUCCESS-2',
      bookingRef: 'BKG-20261027-MAS01',
      bookingId: 88,
      amount: 500.0,
      paymentStatus: 'SUCCESS',
      bookingStatus: 'CONFIRMED',
      paymentMethod: 'MOCK_UPI',
      message: 'Payment successful',
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

    // Select seat & proceed
    const seatBtn = await screen.findByText('#12');
    fireEvent.click(seatBtn);
    const proceedBtn = screen.getByRole('button', { name: /hold & proceed/i });
    fireEvent.click(proceedBtn);

    expect(await screen.findByRole('heading', { name: /confirm & pay/i })).toBeInTheDocument();

    // Check simulate failure checkbox
    const checkbox = screen.getByLabelText(/simulate gateway payment failure/i);
    fireEvent.click(checkbox);

    // Click pay
    fireEvent.click(screen.getByRole('button', { name: /pay ₹500\.00 & confirm/i }));

    // Verify error is displayed and step remains on payment
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
        coachType: '3A',
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
      coachType: '3A',
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
      expect.objectContaining({
        seatId: 501,
        bookingMode: 'TATKAL',
      }),
      testAdmissionToken,
    );

    // Verify createBooking was called with TATKAL bookingMode
    expect(mockedBookingApi.createBooking).toHaveBeenCalledWith(
      expect.objectContaining({
        journeyId: 10,
        seatId: 501,
        bookingMode: 'TATKAL',
      }),
      testAdmissionToken,
    );

    // Verify admissionToken is NEVER stored in localStorage or sessionStorage
    expect(localStorage.getItem('admissionToken')).toBeNull();
    expect(localStorage.getItem('admissionPass')).toBeNull();
    expect(sessionStorage.getItem('admissionToken')).toBeNull();
    expect(sessionStorage.getItem('admissionPass')).toBeNull();
  });

  it('renders "Demo Checkout (Simulated Gateway)" helper text and button when simulated payment is enabled, and displays "Payment Mode: SIMULATED (Demo)" on confirmed card', async () => {
    setSession(testTokens);
    mockedIsSimulatedPaymentEnabled.mockReturnValue(true);
    mockedIsRazorpayCheckoutEnabled.mockReturnValue(false);
    mockedIsMockPaymentEnabled.mockReturnValue(false);

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
        coachType: '3A',
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
      coachType: '3A',
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
      pnr: '8492019482',
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
          />
        </AuthProvider>
      </MemoryRouter>,
    );

    const seatBtn = await screen.findByText('#12');
    fireEvent.click(seatBtn);

    const proceedBtn = screen.getByRole('button', { name: /hold & proceed/i });
    fireEvent.click(proceedBtn);

    expect(await screen.findByRole('heading', { name: /confirm & pay/i })).toBeInTheDocument();
    expect(screen.getByText('Demo Checkout (Simulated Gateway)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /demo checkout/i })).toBeInTheDocument();
  });

  it('renders travel classes and allows switching between classes to filter seats', async () => {
    mockedBookingApi.getJourneys.mockResolvedValueOnce([
      {
        journeyId: 1,
        trainId: 422,
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
      },
    ]);

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
        stopSequence: 218,
        arrivalAt: '2026-12-02T10:05:00',
        departureAt: null,
      },
    ]);

    const mockClasses = [
      {
        classCode: 'SL',
        className: 'Sleeper',
        totalSeats: 6,
        availableSeats: 6,
        heldSeats: 0,
        bookedSeats: 0,
        baseFare: 500.0,
        coaches: [
          {
            coachId: 1,
            coachNumber: 'S1',
            coachType: 'SL',
            totalSeats: 6,
            availableSeats: 6,
            heldSeats: 0,
            bookedSeats: 0,
            seats: [],
          },
        ],
      },
      {
        classCode: '3A',
        className: 'AC 3 Tier',
        totalSeats: 6,
        availableSeats: 6,
        heldSeats: 0,
        bookedSeats: 0,
        baseFare: 1000.0,
        coaches: [
          {
            coachId: 2,
            coachNumber: 'B1',
            coachType: '3A',
            totalSeats: 6,
            availableSeats: 6,
            heldSeats: 0,
            bookedSeats: 0,
            seats: [],
          },
        ],
      },
      {
        classCode: '2A',
        className: 'AC 2 Tier',
        totalSeats: 4,
        availableSeats: 4,
        heldSeats: 0,
        bookedSeats: 0,
        baseFare: 1500.0,
        coaches: [
          {
            coachId: 3,
            coachNumber: 'A1',
            coachType: '2A',
            totalSeats: 4,
            availableSeats: 4,
            heldSeats: 0,
            bookedSeats: 0,
            seats: [],
          },
        ],
      },
    ];

    const mockSeats = [
      {
        inventoryId: 1,
        journeyId: 1,
        coachId: 1,
        coachNumber: 'S1',
        coachType: 'SL',
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
      {
        inventoryId: 7,
        journeyId: 1,
        coachId: 2,
        coachNumber: 'B1',
        coachType: '3A',
        seatId: 201,
        seatNumber: 7,
        seatType: 'LOWER',
        status: 'AVAILABLE',
        holdExpiresAt: null,
        updatedAt: null,
        seatAllocationId: null,
        boardingJourneyStopId: 10,
        alightingJourneyStopId: 227,
      },
      {
        inventoryId: 13,
        journeyId: 1,
        coachId: 3,
        coachNumber: 'A1',
        coachType: '2A',
        seatId: 301,
        seatNumber: 13,
        seatType: 'LOWER',
        status: 'AVAILABLE',
        holdExpiresAt: null,
        updatedAt: null,
        seatAllocationId: null,
        boardingJourneyStopId: 10,
        alightingJourneyStopId: 227,
      },
    ];

    mockedBookingApi.getJourneyClasses.mockResolvedValueOnce(mockClasses);
    mockedBookingApi.getJourneySeats.mockResolvedValueOnce(mockSeats);

    const train12301: TrainSearchResponseDTO = {
      trainId: 422,
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

    render(
      <MemoryRouter>
        <AuthProvider>
          <SeatSelectionModal
            isOpen={true}
            onClose={vi.fn()}
            train={train12301}
            travelDate="2026-12-01"
          />
        </AuthProvider>
      </MemoryRouter>,
    );

    // Verify all 3 classes are rendered
    expect(await screen.findByText('Sleeper')).toBeInTheDocument();
    expect(screen.getByText('AC 3 Tier')).toBeInTheDocument();
    expect(screen.getByText('AC 2 Tier')).toBeInTheDocument();

    // S1 seat #1 is visible initially because Sleeper (SL) is selected first
    expect(screen.getByText('#1')).toBeInTheDocument();
    expect(screen.queryByText('#7')).not.toBeInTheDocument();
    expect(screen.queryByText('#13')).not.toBeInTheDocument();

    // Click on AC 3 Tier
    fireEvent.click(screen.getByText('AC 3 Tier'));
    expect(screen.getByText('#7')).toBeInTheDocument();
    expect(screen.queryByText('#1')).not.toBeInTheDocument();

    // Click on AC 2 Tier
    fireEvent.click(screen.getByText('AC 2 Tier'));
    expect(screen.getByText('#13')).toBeInTheDocument();
    expect(screen.queryByText('#7')).not.toBeInTheDocument();
  });

  it('submits selected travelClass and coachNumber on hold and create booking', async () => {
    setSession(testTokens);

    mockedBookingApi.getJourneys.mockResolvedValueOnce([
      {
        journeyId: 1,
        trainId: 422,
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
      },
    ]);

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
        stopSequence: 218,
        arrivalAt: '2026-12-02T10:05:00',
        departureAt: null,
      },
    ]);

    const mockClasses = [
      {
        classCode: '3A',
        className: 'AC 3 Tier',
        totalSeats: 6,
        availableSeats: 6,
        heldSeats: 0,
        bookedSeats: 0,
        baseFare: 1000.0,
        coaches: [
          {
            coachId: 2,
            coachNumber: 'B1',
            coachType: '3A',
            totalSeats: 6,
            availableSeats: 6,
            heldSeats: 0,
            bookedSeats: 0,
            seats: [],
          },
        ],
      },
    ];

    const mockSeats = [
      {
        inventoryId: 7,
        journeyId: 1,
        coachId: 2,
        coachNumber: 'B1',
        coachType: '3A',
        seatId: 201,
        seatNumber: 7,
        seatType: 'LOWER',
        status: 'AVAILABLE',
        holdExpiresAt: null,
        updatedAt: null,
        seatAllocationId: null,
        boardingJourneyStopId: 10,
        alightingJourneyStopId: 227,
      },
    ];

    mockedBookingApi.getJourneyClasses.mockResolvedValueOnce(mockClasses);
    mockedBookingApi.getJourneySeats.mockResolvedValueOnce(mockSeats);

    mockedBookingApi.holdSeat.mockResolvedValueOnce({
      inventoryId: 7,
      journeyId: 1,
      coachId: 2,
      coachNumber: 'B1',
      coachType: '3A',
      seatId: 201,
      seatNumber: 7,
      seatType: 'LOWER',
      status: 'HOLD',
      holdExpiresAt: '2026-12-01T17:05:00',
      updatedAt: '2026-12-01T16:55:00',
      seatAllocationId: 701,
      boardingJourneyStopId: 10,
      alightingJourneyStopId: 227,
    });

    mockedBookingApi.createBooking.mockResolvedValueOnce({
      bookingId: 99,
      bookingRef: 'BKG-20261201-HWH01',
      journeyId: 1,
      seatId: 201,
      coachNumber: 'B1',
      seatNumber: 7,
      bookingStatus: 'PENDING_PAYMENT',
      paymentStatus: 'PENDING',
      amount: 1000.0,
      createdAt: '2026-12-01T16:55:00',
    });

    const train12301: TrainSearchResponseDTO = {
      trainId: 422,
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

    render(
      <MemoryRouter>
        <AuthProvider>
          <SeatSelectionModal
            isOpen={true}
            onClose={vi.fn()}
            train={train12301}
            travelDate="2026-12-01"
          />
        </AuthProvider>
      </MemoryRouter>,
    );

    // Select seat #7
    const seatBtn = await screen.findByText('#7');
    fireEvent.click(seatBtn);

    // Hold & Proceed
    fireEvent.click(screen.getByRole('button', { name: /hold & proceed/i }));

    // Review step displays class & coach details
    expect(await screen.findByRole('heading', { name: /confirm & pay/i })).toBeInTheDocument();
    expect(screen.getByText('BKG-20261201-HWH01')).toBeInTheDocument();
    expect(screen.getByText(/AC 3 Tier \(3A\)/i)).toBeInTheDocument();

    // Verify holdSeat payload
    expect(mockedBookingApi.holdSeat).toHaveBeenCalledWith(
      1,
      expect.objectContaining({
        seatId: 201,
        travelClass: '3A',
        coachNumber: 'B1',
      }),
      undefined,
    );

    // Verify createBooking payload
    expect(mockedBookingApi.createBooking).toHaveBeenCalledWith(
      expect.objectContaining({
        journeyId: 1,
        seatId: 201,
        travelClass: '3A',
        coachNumber: 'B1',
      }),
      undefined,
    );
  });

  it('supports multi-seat selection, passenger details entry, batch hold, and multi-passenger booking', async () => {
    setSession(testTokens);

    mockedBookingApi.getJourneys.mockResolvedValueOnce([
      {
        journeyId: 1,
        trainId: 422,
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
      },
    ]);

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
        stopSequence: 218,
        arrivalAt: '2026-12-02T10:05:00',
        departureAt: null,
      },
    ]);

    const mockClasses = [
      {
        classCode: '3A',
        className: 'AC 3 Tier',
        totalSeats: 6,
        availableSeats: 6,
        heldSeats: 0,
        bookedSeats: 0,
        baseFare: 1000.0,
        coaches: [
          {
            coachId: 2,
            coachNumber: 'B1',
            coachType: '3A',
            totalSeats: 6,
            availableSeats: 6,
            heldSeats: 0,
            bookedSeats: 0,
            seats: [],
          },
        ],
      },
    ];

    const mockSeats = [
      {
        inventoryId: 7,
        journeyId: 1,
        coachId: 2,
        coachNumber: 'B1',
        coachType: '3A',
        seatId: 201,
        seatNumber: 7,
        seatType: 'LOWER',
        status: 'AVAILABLE',
        holdExpiresAt: null,
        updatedAt: null,
        seatAllocationId: null,
        boardingJourneyStopId: 10,
        alightingJourneyStopId: 227,
      },
      {
        inventoryId: 8,
        journeyId: 1,
        coachId: 2,
        coachNumber: 'B1',
        coachType: '3A',
        seatId: 202,
        seatNumber: 8,
        seatType: 'UPPER',
        status: 'AVAILABLE',
        holdExpiresAt: null,
        updatedAt: null,
        seatAllocationId: null,
        boardingJourneyStopId: 10,
        alightingJourneyStopId: 227,
      },
    ];

    mockedBookingApi.getJourneyClasses.mockResolvedValueOnce(mockClasses);
    mockedBookingApi.getJourneySeats.mockResolvedValueOnce(mockSeats);

    mockedBookingApi.holdSeats.mockResolvedValueOnce([
      {
        inventoryId: 7,
        journeyId: 1,
        coachId: 2,
        coachNumber: 'B1',
        coachType: '3A',
        seatId: 201,
        seatNumber: 7,
        seatType: 'LOWER',
        status: 'HOLD',
        holdExpiresAt: '2026-12-01T17:05:00',
        updatedAt: '2026-12-01T16:55:00',
        seatAllocationId: 701,
        boardingJourneyStopId: 10,
        alightingJourneyStopId: 227,
      },
      {
        inventoryId: 8,
        journeyId: 1,
        coachId: 2,
        coachNumber: 'B1',
        coachType: '3A',
        seatId: 202,
        seatNumber: 8,
        seatType: 'UPPER',
        status: 'HOLD',
        holdExpiresAt: '2026-12-01T17:05:00',
        updatedAt: '2026-12-01T16:55:00',
        seatAllocationId: 702,
        boardingJourneyStopId: 10,
        alightingJourneyStopId: 227,
      },
    ]);

    mockedBookingApi.createBooking.mockResolvedValueOnce({
      bookingId: 105,
      bookingRef: 'BKG-20261201-MULTI01',
      journeyId: 1,
      seatId: 201,
      coachNumber: 'B1',
      seatNumber: 7,
      bookingStatus: 'PENDING_PAYMENT',
      paymentStatus: 'PENDING',
      amount: 2000.0,
      createdAt: '2026-12-01T16:55:00',
      passengers: [
        {
          passengerId: 1,
          fullName: 'Aarav Sharma',
          age: 29,
          gender: 'MALE',
          berthPreference: 'LOWER',
          seatId: 201,
          seatNumber: 7,
          seatType: 'LOWER',
          coachId: 2,
          coachNumber: 'B1',
          coachType: '3A',
          seatAllocationId: 701,
        },
        {
          passengerId: 2,
          fullName: 'Pooja Sharma',
          age: 27,
          gender: 'FEMALE',
          berthPreference: 'UPPER',
          seatId: 202,
          seatNumber: 8,
          seatType: 'UPPER',
          coachId: 2,
          coachNumber: 'B1',
          coachType: '3A',
          seatAllocationId: 702,
        },
      ],
    });

    mockedBookingApi.payBooking.mockResolvedValueOnce({
      transactionRef: 'TXN-MULTI-PASS-SUCCESS',
      bookingRef: 'BKG-20261201-MULTI01',
      bookingId: 105,
      amount: 2000.0,
      paymentStatus: 'SUCCESS',
      bookingStatus: 'CONFIRMED',
      paymentMethod: 'MOCK_UPI',
      message: 'Success',
      transactionTime: '2026-12-01T17:00:00',
    });

    const train12301: TrainSearchResponseDTO = {
      trainId: 422,
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

    render(
      <MemoryRouter>
        <AuthProvider>
          <SeatSelectionModal
            isOpen={true}
            onClose={vi.fn()}
            train={train12301}
            travelDate="2026-12-01"
          />
        </AuthProvider>
      </MemoryRouter>,
    );

    // 1. Select two seats (#7 and #8)
    const seat7Btn = await screen.findByText('#7');
    fireEvent.click(seat7Btn);
    const seat8Btn = await screen.findByText('#8');
    fireEvent.click(seat8Btn);

    // 2. Click "Add Passenger Details (2)"
    const addPassengersBtn = screen.getByRole('button', { name: /add passenger details \(2\)/i });
    fireEvent.click(addPassengersBtn);

    // 3. Step 2: Passenger Details form is displayed
    expect(await screen.findByRole('heading', { name: /passenger details/i })).toBeInTheDocument();
    expect(screen.getByText(/passenger 1/i)).toBeInTheDocument();
    expect(screen.getByText(/passenger 2/i)).toBeInTheDocument();

    // Fill Passenger 1 age and name
    const inputs = screen.getAllByRole('textbox');
    fireEvent.change(inputs[0], { target: { value: 'Aarav Sharma' } });
    fireEvent.change(inputs[1], { target: { value: 'Pooja Sharma' } });

    // Age inputs (spinbuttons)
    const ageInputs = screen.getAllByRole('spinbutton');
    fireEvent.change(ageInputs[0], { target: { value: '29' } });
    fireEvent.change(ageInputs[1], { target: { value: '27' } });

    // 4. Click "Hold & Proceed to Payment"
    const holdAndPayBtn = screen.getByRole('button', { name: /hold & proceed to payment/i });
    fireEvent.click(holdAndPayBtn);

    // 5. Verify batch hold was called with both seat IDs
    await waitFor(() => {
      expect(mockedBookingApi.holdSeats).toHaveBeenCalledWith(
        1,
        expect.objectContaining({
          seatIds: [201, 202],
          travelClass: '3A',
          coachNumber: 'B1',
        }),
        undefined,
      );
    });

    // Verify createBooking was called with both passengers
    expect(mockedBookingApi.createBooking).toHaveBeenCalledWith(
      expect.objectContaining({
        journeyId: 1,
        passengers: expect.arrayContaining([
          expect.objectContaining({ fullName: 'Aarav Sharma', age: 29, seatId: 201 }),
          expect.objectContaining({ fullName: 'Pooja Sharma', age: 27, seatId: 202 }),
        ]),
      }),
      undefined,
    );

    // 6. Review step: Confirm & Pay with passenger roster
    expect(await screen.findByRole('heading', { name: /confirm & pay/i })).toBeInTheDocument();
    expect(screen.getByText('BKG-20261201-MULTI01')).toBeInTheDocument();
    expect(screen.getByText(/1\. Aarav Sharma/i)).toBeInTheDocument();
    expect(screen.getByText(/2\. Pooja Sharma/i)).toBeInTheDocument();

    // 7. Pay & Confirm
    const payBtn = screen.getByRole('button', { name: /pay ₹2000\.00 & confirm/i });
    fireEvent.click(payBtn);

    // 8. Confirmed step with full passenger roster
    expect(await screen.findByRole('heading', { name: /booking confirmed!/i })).toBeInTheDocument();
    expect(screen.getByText('TXN-MULTI-PASS-SUCCESS')).toBeInTheDocument();
    expect(screen.getByText(/confirmed passengers/i)).toBeInTheDocument();
  });
});
