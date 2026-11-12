import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from '@/features/auth/AuthContext';
import { clearSession, setSession } from '@/features/auth/session';
import HomePage from '@/pages/HomePage';
import LoginPage from '@/pages/LoginPage';
import { TrainSearchForm } from '@/features/search/components/TrainSearchForm';
import { TrainList } from '@/features/search/components/TrainList';
import { TrainCard } from '@/features/search/components/TrainCard';
import {
  SeatSelectionModal,
  PENDING_BOOKING_STORAGE_KEY,
  type PendingBookingIntent,
} from '@/features/booking/components/SeatSelectionModal';
import { bookingApi } from '@/features/booking/api';
import { authApi } from '@/features/auth/api';
import type { TrainSearchResponseDTO } from '@/features/search/types';
import type {
  JourneyDTO,
  JourneyStopDTO,
  SeatAvailabilityDTO,
  TravelClassAvailabilityDTO,
} from '@/features/booking/types';
import type { AuthResponse, UserResponse } from '@/features/auth/types';

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
  trainId: 1,
  trainNumber: '12301',
  trainName: 'Howrah Rajdhani Express',
  fromStationCode: 'HWH',
  fromStationName: 'Howrah Junction',
  toStationCode: 'NDLS',
  toStationName: 'New Delhi',
  fromArrivalTime: null,
  fromDepartureTime: '16:55:00',
  toArrivalTime: '10:05:00',
  toDepartureTime: null,
  fromDayOffset: 0,
  toDayOffset: 1,
};

const sampleJourney: JourneyDTO = {
  journeyId: 1,
  trainId: 1,
  trainNumber: '12301',
  trainName: 'Howrah Rajdhani Express',
  trainRouteId: 1,
  fromStationId: 1,
  fromStationCode: 'HWH',
  fromStationName: 'Howrah Junction',
  toStationId: 2,
  toStationCode: 'NDLS',
  toStationName: 'New Delhi',
  journeyDate: '2026-12-01',
  departureTime: '16:55:00',
  arrivalTime: '10:05:00',
  departureAt: '2026-12-01T16:55:00',
  arrivalAt: '2026-12-02T10:05:00',
};

const sampleStops: JourneyStopDTO[] = [
  {
    journeyStopId: 10,
    stationId: 1,
    stationCode: 'HWH',
    stationName: 'Howrah Junction',
    stopSequence: 1,
    arrivalAt: null,
    departureAt: '2026-12-01T16:55:00',
  },
  {
    journeyStopId: 11,
    stationId: 2,
    stationCode: 'NDLS',
    stationName: 'New Delhi',
    stopSequence: 2,
    arrivalAt: '2026-12-02T10:05:00',
    departureAt: null,
  },
];

const sampleClasses: TravelClassAvailabilityDTO[] = [
  {
    classCode: '2A',
    className: 'AC 2 Tier',
    totalSeats: 6,
    availableSeats: 6,
    heldSeats: 0,
    bookedSeats: 0,
    baseFare: 1500,
    coaches: [
      {
        coachId: 1,
        coachNumber: 'A1',
        coachType: '2A',
        totalSeats: 6,
        availableSeats: 6,
        heldSeats: 0,
        bookedSeats: 0,
        seats: [],
      },
    ],
  },
];

const sampleSeats: SeatAvailabilityDTO[] = [
  {
    inventoryId: 1,
    journeyId: 1,
    seatId: 101,
    coachId: 1,
    coachNumber: 'A1',
    coachType: '2A',
    seatNumber: 1,
    seatType: 'LOWER',
    status: 'AVAILABLE',
    holdExpiresAt: null,
    updatedAt: null,
    seatAllocationId: null,
    boardingJourneyStopId: 10,
    alightingJourneyStopId: 11,
  },
  {
    inventoryId: 2,
    journeyId: 1,
    seatId: 102,
    coachId: 1,
    coachNumber: 'A1',
    coachType: '2A',
    seatNumber: 2,
    seatType: 'UPPER',
    status: 'AVAILABLE',
    holdExpiresAt: null,
    updatedAt: null,
    seatAllocationId: null,
    boardingJourneyStopId: 10,
    alightingJourneyStopId: 11,
  },
];

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

function renderWithProviders(ui: React.ReactElement, initialPath: string = '/') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <AuthProvider>{ui}</AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('Sprint 3: Complete Booking Discovery and Checkout UX Remediation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clearSession();
    sessionStorage.clear();
    mockedAuthApi.getCurrentUser.mockRejectedValue(new Error('Unauthenticated'));
  });

  afterEach(() => {
    clearSession();
    sessionStorage.clear();
  });

  // ─────────────────────────────────────────────────────────────
  // SCOPE 1: Homepage Hero Search Integration
  // ─────────────────────────────────────────────────────────────
  it('Scope 1: HomePage embeds the train search form directly in the hero section', () => {
    renderWithProviders(<HomePage />);

    expect(screen.getByRole('heading', { name: /enterprise railway booking/i })).toBeInTheDocument();
    expect(screen.getByLabelText(/origin station/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/destination station/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/travel date/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /search trains/i })).toBeInTheDocument();
  });

  // ─────────────────────────────────────────────────────────────
  // SCOPE 2: Demo Journey Discovery Action
  // ─────────────────────────────────────────────────────────────
  it('Scope 2: TrainSearchForm provides a one-click demo booking shortcut for Train 12301', () => {
    const handleSearch = vi.fn();
    render(
      <TrainSearchForm
        onSearch={handleSearch}
      />,
    );

    const demoButton = screen.getByRole('button', {
      name: /try demo booking — train 12301 \| hwh → ndls \| 1 dec 2026/i,
    });
    expect(demoButton).toBeInTheDocument();

    fireEvent.click(demoButton);

    expect(handleSearch).toHaveBeenCalledWith({
      from: 'HWH',
      to: 'NDLS',
      date: '2026-12-01',
    });
  });

  // ─────────────────────────────────────────────────────────────
  // SCOPE 3: Journey Date and Inventory Clarity
  // ─────────────────────────────────────────────────────────────
  it('Scope 3: When searching on a non-demo date, TrainCard clearly indicates run not scheduled and offers Switch to 1 Dec 2026', () => {
    const handleSelectDate = vi.fn();

    render(
      <TrainCard
        train={sampleTrain12301}
        travelDate="2026-11-12"
        journey={null}
        isTimetableOnly={true}
        onSelectDate={handleSelectDate}
      />,
    );

    // Timetable Only badge
    expect(screen.getByText('Timetable Only')).toBeInTheDocument();

    // 3 states communicated
    expect(
      screen.getByText(/no scheduled operational run on 2026-11-12/i),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/timetable active • run not scheduled • seats unavailable/i),
    ).toBeInTheDocument();

    // Single click switch action
    const switchButton = screen.getByRole('button', {
      name: /switch to 1 dec 2026 \(demo run\)/i,
    });
    expect(switchButton).toBeInTheDocument();

    fireEvent.click(switchButton);
    expect(handleSelectDate).toHaveBeenCalledWith('2026-12-01');
  });

  it('Scope 3: TrainList banner displays Switch to 1 Dec 2026 button on non-demo dates', () => {
    const handleSelectDate = vi.fn();

    render(
      <TrainList
        trains={[sampleTrain12301]}
        isLoading={false}
        isError={false}
        onRetry={vi.fn()}
        hasSearched={true}
        travelDate="2026-11-12"
        fromCode="HWH"
        toCode="NDLS"
        onSelectDate={handleSelectDate}
      />,
    );

    const switchButtons = screen.getAllByRole('button', {
      name: /switch to 1 dec 2026 \(demo run\)/i,
    });
    expect(switchButtons.length).toBeGreaterThanOrEqual(1);

    fireEvent.click(switchButtons[0]);
    expect(handleSelectDate).toHaveBeenCalledWith('2026-12-01');
  });

  // ─────────────────────────────────────────────────────────────
  // SCOPE 4: Authentication and Booking State Preservation
  // ─────────────────────────────────────────────────────────────
  it('Scope 4: Unauthenticated user selecting seats captures booking intent to sessionStorage and redirects to login', async () => {
    mockedBookingApi.getJourneys.mockResolvedValue([sampleJourney]);
    mockedBookingApi.getJourneyStops.mockResolvedValue(sampleStops);
    mockedBookingApi.getJourneyClasses.mockResolvedValue(sampleClasses);
    mockedBookingApi.getJourneySeats.mockResolvedValue(sampleSeats);

    renderWithProviders(
      <SeatSelectionModal
        isOpen={true}
        onClose={vi.fn()}
        train={sampleTrain12301}
        travelDate="2026-12-01"
        journeyId={1}
      />,
      '/search?from=HWH&to=NDLS&date=2026-12-01',
    );

    // Wait for seats to render
    await waitFor(() => {
      expect(screen.getByText('#1')).toBeInTheDocument();
    });

    // Select berth #1
    fireEvent.click(screen.getByText('#1'));

    // Verify Sign in to Book button is visible
    const signInButton = screen.getByRole('button', { name: /sign in to book/i });
    expect(signInButton).toBeInTheDocument();

    fireEvent.click(signInButton);

    // Verify sessionStorage has saved intent
    const savedRaw = sessionStorage.getItem(PENDING_BOOKING_STORAGE_KEY);
    expect(savedRaw).not.toBeNull();

    const intent = JSON.parse(savedRaw!) as PendingBookingIntent;
    expect(intent.journeyId).toBe(1);
    expect(intent.trainNumber).toBe('12301');
    expect(intent.travelDate).toBe('2026-12-01');
    expect(intent.selectedSeatNumbers).toEqual([1]);
    expect(intent.step).toBe('passengers');
    expect(intent.timestamp).toBeGreaterThan(0);
  });

  it('Scope 4: LoginPage enforces safe return redirect and protects against open redirect exploits', async () => {
    mockedAuthApi.login.mockResolvedValue(testTokens);
    mockedAuthApi.getCurrentUser.mockResolvedValue(testUser);

    renderWithProviders(
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/search" element={<div>Restored Search Page Target</div>} />
        <Route path="/" element={<div>Home Page Target</div>} />
      </Routes>,
      '/login?redirect=%2Fsearch%3Ffrom%3DHWH%26to%3DNDLS%26date%3D2026-12-01%26autoOpenTrain%3D12301',
    );

    fireEvent.change(screen.getByLabelText(/username/i), {
      target: { value: 'passenger' },
    });
    fireEvent.change(screen.getByLabelText(/password/i), {
      target: { value: 'Secret123!' },
    });

    fireEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => {
      expect(screen.getByText('Restored Search Page Target')).toBeInTheDocument();
    });
  });

  it('Scope 4: LoginPage rejects dangerous protocol-relative open redirects (e.g. //evil.com)', async () => {
    mockedAuthApi.login.mockResolvedValue(testTokens);
    mockedAuthApi.getCurrentUser.mockResolvedValue(testUser);

    renderWithProviders(
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<div>Safe Fallback Home Target</div>} />
      </Routes>,
      '/login?redirect=%2F%2Fevil.com%2Fphish',
    );

    fireEvent.change(screen.getByLabelText(/username/i), {
      target: { value: 'passenger' },
    });
    fireEvent.change(screen.getByLabelText(/password/i), {
      target: { value: 'Secret123!' },
    });

    fireEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => {
      // Disallows //evil.com and falls back to ROUTES.HOME ('/')
      expect(screen.getByText('Safe Fallback Home Target')).toBeInTheDocument();
    });
  });

  it('Scope 4: SeatSelectionModal re-validates and restores seat selection from sessionStorage after login', async () => {
    setSession(testTokens);
    mockedAuthApi.getCurrentUser.mockResolvedValue(testUser);

    mockedBookingApi.getJourneys.mockResolvedValue([sampleJourney]);
    mockedBookingApi.getJourneyStops.mockResolvedValue(sampleStops);
    mockedBookingApi.getJourneyClasses.mockResolvedValue(sampleClasses);
    mockedBookingApi.getJourneySeats.mockResolvedValue(sampleSeats);

    // Stage pending booking intent in sessionStorage
    const pendingIntent: PendingBookingIntent = {
      journeyId: 1,
      trainNumber: '12301',
      trainName: 'Howrah Rajdhani Express',
      travelDate: '2026-12-01',
      fromStationCode: 'HWH',
      toStationCode: 'NDLS',
      selectedClassCode: '2A',
      selectedCoachNumber: 'A1',
      selectedSeatNumbers: [1],
      step: 'passengers',
      timestamp: Date.now(),
    };
    sessionStorage.setItem(PENDING_BOOKING_STORAGE_KEY, JSON.stringify(pendingIntent));

    renderWithProviders(
      <SeatSelectionModal
        isOpen={true}
        onClose={vi.fn()}
        train={sampleTrain12301}
        travelDate="2026-12-01"
        journeyId={1}
      />,
      '/search?from=HWH&to=NDLS&date=2026-12-01&autoOpenTrain=12301',
    );

    // Verify restored notification toast appears
    await waitFor(() => {
      expect(
        screen.getByText(/welcome back! your seat selection \(#1\) has been restored/i),
      ).toBeInTheDocument();
    });

    // Verify it automatically advanced to passengers step
    expect(screen.getByText(/1 passenger/i)).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/e\.g\. aarav sharma/i)).toBeInTheDocument();

    // Verify sessionStorage has been cleaned up safely
    expect(sessionStorage.getItem(PENDING_BOOKING_STORAGE_KEY)).toBeNull();
  });

  it('Scope 4: SeatSelectionModal warns traveler if seat was booked while signing in', async () => {
    setSession(testTokens);
    mockedAuthApi.getCurrentUser.mockResolvedValue(testUser);

    mockedBookingApi.getJourneys.mockResolvedValue([sampleJourney]);
    mockedBookingApi.getJourneyStops.mockResolvedValue(sampleStops);
    mockedBookingApi.getJourneyClasses.mockResolvedValue(sampleClasses);

    // Mock that seat #1 is now BOOKED (taken by someone else while user was on login screen)
    const takenSeats: SeatAvailabilityDTO[] = [
      {
        inventoryId: 1,
        journeyId: 1,
        seatId: 101,
        coachId: 1,
        coachNumber: 'A1',
        coachType: '2A',
        seatNumber: 1,
        seatType: 'LOWER',
        status: 'BOOKED',
        holdExpiresAt: null,
        updatedAt: null,
        seatAllocationId: null,
        boardingJourneyStopId: 10,
        alightingJourneyStopId: 11,
      },
      {
        inventoryId: 2,
        journeyId: 1,
        seatId: 102,
        coachId: 1,
        coachNumber: 'A1',
        coachType: '2A',
        seatNumber: 2,
        seatType: 'UPPER',
        status: 'AVAILABLE',
        holdExpiresAt: null,
        updatedAt: null,
        seatAllocationId: null,
        boardingJourneyStopId: 10,
        alightingJourneyStopId: 11,
      },
    ];
    mockedBookingApi.getJourneySeats.mockResolvedValue(takenSeats);

    const pendingIntent: PendingBookingIntent = {
      journeyId: 1,
      trainNumber: '12301',
      trainName: 'Howrah Rajdhani Express',
      travelDate: '2026-12-01',
      fromStationCode: 'HWH',
      toStationCode: 'NDLS',
      selectedClassCode: '2A',
      selectedCoachNumber: 'A1',
      selectedSeatNumbers: [1],
      step: 'passengers',
      timestamp: Date.now(),
    };
    sessionStorage.setItem(PENDING_BOOKING_STORAGE_KEY, JSON.stringify(pendingIntent));

    renderWithProviders(
      <SeatSelectionModal
        isOpen={true}
        onClose={vi.fn()}
        train={sampleTrain12301}
        travelDate="2026-12-01"
        journeyId={1}
      />,
    );

    // Verify warning that seats are no longer available
    await waitFor(() => {
      expect(
        screen.getByText(/your previously selected seats are no longer available/i),
      ).toBeInTheDocument();
    });

    // Should stay on select-seat step to allow re-selection (#2 seat should be visible)
    expect(screen.getByText('#2')).toBeInTheDocument();

    // Session storage is cleaned up
    expect(sessionStorage.getItem(PENDING_BOOKING_STORAGE_KEY)).toBeNull();
  });
});
