import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from '@/features/auth/AuthContext';
import { clearSession, setSession } from '@/features/auth/session';
import { bookingApi } from '@/features/booking/api';
import { authApi } from '@/features/auth/api';
import type { BookingDetailResponseDTO, PageResponseDTO } from '@/features/booking/types';
import type { AuthResponse, UserResponse } from '@/features/auth/types';
import BookingsPage from '@/pages/BookingsPage';

vi.mock('@/features/booking/api', () => ({
  bookingApi: {
    getBookingHistory: vi.fn(),
    payBooking: vi.fn(),
    cancelBooking: vi.fn(),
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
  accessToken: 'valid-access-token',
  refreshToken: 'valid-refresh-token',
  tokenType: 'Bearer',
  expiresIn: 3600,
  message: 'Success',
};

const mockBookingsPageResponse: PageResponseDTO<BookingDetailResponseDTO> = {
  content: [
    {
      bookingId: 101,
      bookingRef: 'BKG-20261026-MAS01',
      userId: 1,
      username: 'passenger',
      journeyId: 10,
      trainNumber: '12601',
      trainName: 'Mangalore Mail',
      fromStationCode: 'MAS',
      fromStationName: 'Chennai Central',
      toStationCode: 'SBC',
      toStationName: 'Bangalore City',
      journeyDate: '2026-10-27',
      departureTime: '20:15:00',
      arrivalTime: '04:30:00',
      coachId: 1,
      coachNumber: 'B1',
      coachType: 'AC_3_TIER',
      seatId: 501,
      seatNumber: 24,
      seatType: 'LOWER',
      seatAllocationId: 701,
      bookingStatus: 'CONFIRMED',
      paymentStatus: 'SUCCESS',
      amount: 500.0,
      createdAt: '2026-10-26T12:00:00',
    },
    {
      bookingId: 102,
      bookingRef: 'BKG-20261026-MAS02',
      userId: 1,
      username: 'passenger',
      journeyId: 11,
      trainNumber: '12675',
      trainName: 'Kovai Express',
      fromStationCode: 'MAS',
      fromStationName: 'Chennai Central',
      toStationCode: 'CBE',
      toStationName: 'Coimbatore Junction',
      journeyDate: '2026-10-28',
      departureTime: '06:10:00',
      arrivalTime: '14:05:00',
      coachId: 2,
      coachNumber: 'C1',
      coachType: 'CHAIR_CAR',
      seatId: 502,
      seatNumber: 15,
      seatType: 'WINDOW',
      seatAllocationId: 702,
      bookingStatus: 'PENDING_PAYMENT',
      paymentStatus: 'PENDING',
      amount: 450.0,
      createdAt: '2026-10-26T13:00:00',
    },
  ],
  page: 0,
  size: 10,
  totalElements: 2,
  totalPages: 1,
  first: true,
  last: true,
  hasNext: false,
  hasPrevious: false,
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

describe('BookingsPage experience', () => {
  it('shows sign-in prompt when unauthenticated', async () => {
    render(
      <MemoryRouter initialEntries={['/bookings']}>
        <AuthProvider>
          <Routes>
            <Route path="/bookings" element={<BookingsPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByRole('heading', { name: /sign in to view your bookings/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument();
    expect(mockedBookingApi.getBookingHistory).not.toHaveBeenCalled();
  });

  it('shows empty state when authenticated user has no bookings', async () => {
    setSession(testTokens);
    mockedBookingApi.getBookingHistory.mockResolvedValueOnce({
      content: [],
      page: 0,
      size: 10,
      totalElements: 0,
      totalPages: 0,
      first: true,
      last: true,
      hasNext: false,
      hasPrevious: false,
    });

    render(
      <MemoryRouter initialEntries={['/bookings']}>
        <AuthProvider>
          <Routes>
            <Route path="/bookings" element={<BookingsPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /no bookings found/i })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: /search trains/i })).toBeInTheDocument();
    });
  });

  it('renders booking cards and handles payment action with idempotency key', async () => {
    setSession(testTokens);
    mockedBookingApi.getBookingHistory.mockResolvedValueOnce(mockBookingsPageResponse);
    mockedBookingApi.payBooking.mockResolvedValueOnce({
      transactionRef: 'TXN-PAY-1',
      bookingRef: 'BKG-20261026-MAS02',
      bookingId: 102,
      amount: 450.0,
      paymentStatus: 'SUCCESS',
      bookingStatus: 'CONFIRMED',
      paymentMethod: 'MOCK_UPI',
      message: 'Success',
      transactionTime: '2026-10-26T14:00:00',
    });

    render(
      <MemoryRouter initialEntries={['/bookings']}>
        <AuthProvider>
          <Routes>
            <Route path="/bookings" element={<BookingsPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByRole('heading', { name: 'My Bookings' })).toBeInTheDocument();
    expect(await screen.findByText('12601 — Mangalore Mail')).toBeInTheDocument();
    expect(await screen.findByText('12675 — Kovai Express')).toBeInTheDocument();
    expect(screen.getByText(/PNR: BKG-20261026-MAS01/)).toBeInTheDocument();
    expect(screen.getByText('Coach B1, #24')).toBeInTheDocument();
    expect(screen.getByText('CONFIRMED')).toBeInTheDocument();

    const payNowBtn = screen.getByRole('button', { name: /pay now/i });
    expect(payNowBtn).toBeInTheDocument();

    // Re-mock getBookingHistory for reload after payment
    mockedBookingApi.getBookingHistory.mockResolvedValueOnce({
      ...mockBookingsPageResponse,
      content: [
        mockBookingsPageResponse.content[0],
        {
          ...mockBookingsPageResponse.content[1],
          bookingStatus: 'CONFIRMED',
          paymentStatus: 'SUCCESS',
        },
      ],
    });

    fireEvent.click(payNowBtn);
    expect(mockedBookingApi.payBooking).toHaveBeenCalledWith(
      'BKG-20261026-MAS02',
      expect.objectContaining({
        paymentMethod: 'MOCK_UPI',
        idempotencyKey: expect.stringMatching(/^PAY-/),
        simulateFailure: false,
      }),
    );
  });

  it('displays error alert banner when payment fails and allows retry', async () => {
    setSession(testTokens);
    mockedBookingApi.getBookingHistory.mockResolvedValueOnce(mockBookingsPageResponse);
    mockedBookingApi.payBooking.mockResolvedValueOnce({
      transactionRef: 'TXN-FAIL-1',
      bookingRef: 'BKG-20261026-MAS02',
      bookingId: 102,
      amount: 450.0,
      paymentStatus: 'FAILED',
      bookingStatus: 'PENDING_PAYMENT',
      paymentMethod: 'MOCK_UPI',
      message: 'Card limit exceeded',
      transactionTime: '2026-10-26T14:00:00',
    }).mockResolvedValueOnce({
      transactionRef: 'TXN-RETRY-1',
      bookingRef: 'BKG-20261026-MAS02',
      bookingId: 102,
      amount: 450.0,
      paymentStatus: 'SUCCESS',
      bookingStatus: 'CONFIRMED',
      paymentMethod: 'MOCK_UPI',
      message: 'Success',
      transactionTime: '2026-10-26T14:01:00',
    });

    render(
      <MemoryRouter initialEntries={['/bookings']}>
        <AuthProvider>
          <Routes>
            <Route path="/bookings" element={<BookingsPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    const payNowBtn = await screen.findByRole('button', { name: /pay now/i });
    fireEvent.click(payNowBtn);

    // Verify error banner is rendered with message
    expect(await screen.findByRole('alert')).toHaveTextContent(/Card limit exceeded/i);

    const failedAttempt = mockedBookingApi.payBooking.mock.calls[0][1];
    fireEvent.click(screen.getByRole('button', { name: /pay now/i }));
    await waitFor(() => expect(mockedBookingApi.payBooking).toHaveBeenCalledTimes(2));
    const retryAttempt = mockedBookingApi.payBooking.mock.calls[1][1];
    expect(retryAttempt?.idempotencyKey).not.toBe(failedAttempt?.idempotencyKey);
  });

  it('reuses the same payment key after a timeout and blocks duplicate clicks in flight', async () => {
    setSession(testTokens);
    mockedBookingApi.getBookingHistory.mockResolvedValue(mockBookingsPageResponse);

    let rejectPayment!: (reason: Error) => void;
    mockedBookingApi.payBooking
      .mockImplementationOnce(
        () => new Promise((_, reject) => { rejectPayment = reject; }),
      )
      .mockResolvedValueOnce({
        transactionRef: 'TXN-REPLAY-1',
        bookingRef: 'BKG-20261026-MAS02',
        bookingId: 102,
        amount: 450.0,
        paymentStatus: 'SUCCESS',
        bookingStatus: 'CONFIRMED',
        paymentMethod: 'MOCK_UPI',
        message: 'Success',
        transactionTime: '2026-10-26T14:00:00',
      });

    render(
      <MemoryRouter initialEntries={['/bookings']}>
        <AuthProvider>
          <Routes>
            <Route path="/bookings" element={<BookingsPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    const payNowButton = await screen.findByRole('button', { name: /pay now/i });
    fireEvent.click(payNowButton);
    fireEvent.click(payNowButton);
    expect(mockedBookingApi.payBooking).toHaveBeenCalledTimes(1);
    const firstAttempt = mockedBookingApi.payBooking.mock.calls[0][1];

    // The server may have completed the operation even though the client timed out.
    rejectPayment(new Error('network timeout'));
    expect(await screen.findByRole('alert')).toHaveTextContent(/network timeout/i);
    fireEvent.click(screen.getByRole('button', { name: /pay now/i }));

    expect(mockedBookingApi.payBooking).toHaveBeenCalledTimes(2);
    expect(mockedBookingApi.payBooking.mock.calls[1][1]).toEqual(firstAttempt);
    await waitFor(() => expect(mockedBookingApi.getBookingHistory).toHaveBeenCalledTimes(2));
  });

  it('opens cancellation confirmation modal with refund notice and allows dismissal', async () => {
    setSession(testTokens);
    mockedBookingApi.getBookingHistory.mockResolvedValueOnce(mockBookingsPageResponse);

    render(
      <MemoryRouter initialEntries={['/bookings']}>
        <AuthProvider>
          <Routes>
            <Route path="/bookings" element={<BookingsPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    const cancelBtn = await screen.findByRole('button', { name: /cancel booking bkg-20261026-mas01/i });
    expect(cancelBtn).toBeInTheDocument();

    fireEvent.click(cancelBtn);

    const modal = await screen.findByRole('dialog');
    expect(modal).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /confirm cancellation/i })).toBeInTheDocument();
    expect(screen.getByText(/automated refunds are not supported/i)).toBeInTheDocument();

    // Dismiss by clicking Keep Booking
    const keepBtn = screen.getByRole('button', { name: /keep booking/i });
    fireEvent.click(keepBtn);

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(mockedBookingApi.cancelBooking).not.toHaveBeenCalled();
  });

  it('cancels confirmed booking and updates status upon confirmation', async () => {
    setSession(testTokens);
    mockedBookingApi.getBookingHistory.mockResolvedValueOnce(mockBookingsPageResponse);
    mockedBookingApi.cancelBooking.mockResolvedValueOnce({
      ...mockBookingsPageResponse.content[0],
      bookingStatus: 'CANCELLED',
    });

    render(
      <MemoryRouter initialEntries={['/bookings']}>
        <AuthProvider>
          <Routes>
            <Route path="/bookings" element={<BookingsPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    const cancelBtn = await screen.findByRole('button', { name: /cancel booking bkg-20261026-mas01/i });
    fireEvent.click(cancelBtn);

    const confirmBtn = await screen.findByRole('button', { name: /confirm cancellation/i });

    // Mock history reload with cancelled status
    mockedBookingApi.getBookingHistory.mockResolvedValueOnce({
      ...mockBookingsPageResponse,
      content: [
        {
          ...mockBookingsPageResponse.content[0],
          bookingStatus: 'CANCELLED',
        },
        mockBookingsPageResponse.content[1],
      ],
    });

    fireEvent.click(confirmBtn);

    expect(mockedBookingApi.cancelBooking).toHaveBeenCalledWith('BKG-20261026-MAS01');
    expect(await screen.findByRole('status')).toHaveTextContent(/has been cancelled successfully/i);
    await waitFor(() => expect(mockedBookingApi.getBookingHistory).toHaveBeenCalledTimes(2));
  });

  it('displays error alert banner when cancellation fails', async () => {
    setSession(testTokens);
    mockedBookingApi.getBookingHistory.mockResolvedValueOnce(mockBookingsPageResponse);
    mockedBookingApi.cancelBooking.mockRejectedValueOnce({
      response: {
        data: {
          message: 'Booking has already been cancelled',
        },
      },
    });

    render(
      <MemoryRouter initialEntries={['/bookings']}>
        <AuthProvider>
          <Routes>
            <Route path="/bookings" element={<BookingsPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    const cancelBtn = await screen.findByRole('button', { name: /cancel booking bkg-20261026-mas01/i });
    fireEvent.click(cancelBtn);

    const confirmBtn = await screen.findByRole('button', { name: /confirm cancellation/i });
    fireEvent.click(confirmBtn);

    expect(await screen.findByRole('alert')).toHaveTextContent(/booking has already been cancelled/i);
  });
});
