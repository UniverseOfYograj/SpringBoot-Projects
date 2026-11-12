import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Header from '@/components/layout/Header';
import BookingsPage from '@/pages/BookingsPage';
import { AuthProvider } from '@/features/auth/AuthContext';
import { clearSession, setSession } from '@/features/auth/session';
import { bookingApi } from '@/features/booking/api';
import { authApi } from '@/features/auth/api';
import { ROUTES } from '@/lib/constants';
import type { BookingDetailResponseDTO } from '@/features/booking/types';
import type { UserResponse, AuthResponse } from '@/features/auth/types';

vi.mock('@/features/booking/api', () => ({
  bookingApi: {
    getBookingHistory: vi.fn(),
    getBookingByPnr: vi.fn(),
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

const samplePnrBooking: BookingDetailResponseDTO = {
  bookingId: 301,
  bookingRef: 'BKG-20261201-12301',
  pnr: '9847291842',
  userId: 1,
  username: 'passenger',
  journeyId: 1,
  trainNumber: '12301',
  trainName: 'Howrah Rajdhani Express',
  fromStationCode: 'HWH',
  fromStationName: 'Howrah Junction',
  toStationCode: 'NDLS',
  toStationName: 'New Delhi',
  journeyDate: '2026-12-01',
  departureTime: '16:55:00',
  arrivalTime: '10:05:00',
  coachId: 10,
  coachNumber: 'A1',
  coachType: 'AC_2_TIER',
  seatId: 101,
  seatNumber: 1,
  seatType: 'LOWER',
  seatAllocationId: 401,
  bookingStatus: 'CONFIRMED',
  paymentStatus: 'SUCCESS',
  amount: 2573.0,
  createdAt: '2026-11-12T10:00:00',
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
  accessToken: 'valid-test-token',
  refreshToken: 'valid-refresh-token',
  tokenType: 'Bearer',
  expiresIn: 3600,
  message: 'Success',
};

function renderWithProviders(initialEntry = '/bookings') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <AuthProvider>
          <Routes>
            <Route path={ROUTES.BOOKINGS} element={<BookingsPage />} />
            <Route path={ROUTES.PNR} element={<BookingsPage />} />
            <Route path={ROUTES.SEARCH} element={<div>Search Trains Target Page</div>} />
            <Route path={ROUTES.LOGIN} element={<div>Login Target Page</div>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('Sprint 1 Critical UX Remediation — Automated Verification', () => {
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

  describe('Scope A — Public PNR Enquiry without Login', () => {
    it('allows unauthenticated users to access PNR enquiry via /bookings?tab=pnr directly', async () => {
      renderWithProviders('/bookings?tab=pnr');

      // Verify that PNR enquiry search form is immediately visible, NOT blocked by sign-in prompt
      expect(screen.getByRole('heading', { name: /pnr status enquiry/i })).toBeInTheDocument();
      expect(screen.getByRole('search', { name: /pnr status enquiry/i })).toBeInTheDocument();
      expect(screen.getByLabelText(/enter 10-digit pnr number/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /check status/i })).toBeInTheDocument();

      // Ensure private booking history API was not called
      expect(mockedBookingApi.getBookingHistory).not.toHaveBeenCalled();
    });

    it('allows unauthenticated users to access PNR enquiry via /pnr route', async () => {
      renderWithProviders('/pnr');

      expect(screen.getByRole('heading', { name: /pnr status enquiry/i })).toBeInTheDocument();
      expect(screen.getByRole('search', { name: /pnr status enquiry/i })).toBeInTheDocument();
      expect(screen.getByLabelText(/enter 10-digit pnr number/i)).toBeInTheDocument();
    });

    it('allows unauthenticated users to switch from My Bookings tab to PNR Status tab', async () => {
      renderWithProviders('/bookings');

      // Initially on history tab: shows the sign-in prompt for My Bookings
      expect(screen.getByRole('heading', { name: /sign in to view your bookings/i })).toBeInTheDocument();

      // The tab navigation is still fully available
      const pnrTab = screen.getByRole('tab', { name: /pnr status enquiry/i });
      expect(pnrTab).toBeInTheDocument();

      // Click the PNR Status Enquiry tab
      fireEvent.click(pnrTab);

      // Now the PNR Enquiry interface is rendered
      expect(await screen.findByRole('search', { name: /pnr status enquiry/i })).toBeInTheDocument();
      expect(screen.getByLabelText(/enter 10-digit pnr number/i)).toBeInTheDocument();
    });

    it('executes PNR enquiry and displays booking details when valid 10-digit PNR is submitted', async () => {
      mockedBookingApi.getBookingByPnr.mockResolvedValueOnce(samplePnrBooking);

      renderWithProviders('/pnr');

      const input = screen.getByLabelText(/enter 10-digit pnr number/i);
      fireEvent.change(input, { target: { value: '9847291842' } });

      const checkBtn = screen.getByRole('button', { name: /check status/i });
      fireEvent.click(checkBtn);

      expect(mockedBookingApi.getBookingByPnr).toHaveBeenCalledWith('9847291842');

      // Verify booking reservation details render
      expect(await screen.findByText(/pnr: 9847291842/i)).toBeInTheDocument();
      expect(screen.getByText('12301 — Howrah Rajdhani Express')).toBeInTheDocument();
      expect(screen.getByText('CONFIRMED')).toBeInTheDocument();
      expect(screen.getByText('SUCCESS')).toBeInTheDocument();
      expect(screen.getByText('HWH')).toBeInTheDocument();
      expect(screen.getByText('NDLS')).toBeInTheDocument();
      expect(screen.getByText('A1 / #1')).toBeInTheDocument();
    });

    it('preserves authenticated user booking history when user is logged in', async () => {
      setSession(testTokens);
      mockedBookingApi.getBookingHistory.mockResolvedValueOnce({
        content: [samplePnrBooking],
        page: 0,
        size: 10,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
        hasNext: false,
        hasPrevious: false,
      });

      renderWithProviders('/bookings');

      await waitFor(() => {
        expect(mockedBookingApi.getBookingHistory).toHaveBeenCalled();
        expect(screen.getByText('12301 — Howrah Rajdhani Express')).toBeInTheDocument();
      });
    });
  });

  describe('Scope B — Header Navigation Contrast and Legibility', () => {
    it('renders Header with solid high-contrast brand navy background', () => {
      render(
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <MemoryRouter initialEntries={['/search']}>
            <AuthProvider>
              <Header />
            </AuthProvider>
          </MemoryRouter>
        </QueryClientProvider>,
      );

      const header = screen.getByRole('banner');
      expect(header).toBeInTheDocument();

      // Verify header has solid navy-700 background (NOT broken /98 opacity modifier)
      expect(header.className).toContain('bg-navy-700');
      expect(header.className).not.toContain('/98');
    });

    it('provides high-contrast text and links in navigation bar', () => {
      render(
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <MemoryRouter initialEntries={['/pnr']}>
            <AuthProvider>
              <Header />
            </AuthProvider>
          </MemoryRouter>
        </QueryClientProvider>,
      );

      const pnrLink = screen.getByRole('link', { name: /pnr status/i });
      expect(pnrLink).toBeInTheDocument();
      expect(pnrLink).toHaveAttribute('href', '/pnr');

      // Active tab highlighting on /pnr
      expect(pnrLink.className).toContain('bg-white/20');
      expect(pnrLink.className).toContain('text-white');
    });
  });
});
