import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { PnrEnquiry } from '@/features/booking/components/PnrEnquiry';
import { bookingApi } from '@/features/booking/api';
import type { BookingDetailResponseDTO } from '@/features/booking/types';

vi.mock('@/features/booking/api', () => ({
  bookingApi: {
    getBookingByPnr: vi.fn(),
  },
}));

const mockedBookingApi = vi.mocked(bookingApi);

const mockBookingDetail: BookingDetailResponseDTO = {
  bookingId: 201,
  bookingRef: 'BKG-20261026-MAS01',
  pnr: '8492019482',
  userId: 1,
  username: 'passenger_arun',
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
};

describe('PnrEnquiry Component', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders search input and initial guide state', () => {
    render(
      <MemoryRouter>
        <PnrEnquiry />
      </MemoryRouter>,
    );

    expect(screen.getByRole('search', { name: /pnr status enquiry/i })).toBeInTheDocument();
    expect(screen.getByLabelText(/enter 10-digit pnr number/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /check status/i })).toBeInTheDocument();
    expect(screen.getByText(/where can i find my pnr\?/i)).toBeInTheDocument();
  });

  it('validates empty, incomplete, and non-numeric PNR values client-side', async () => {
    render(
      <MemoryRouter>
        <PnrEnquiry />
      </MemoryRouter>,
    );

    const submitBtn = screen.getByRole('button', { name: /check status/i });

    // 1. Submit empty
    fireEvent.click(submitBtn);
    expect(await screen.findByRole('alert')).toHaveTextContent(/please enter a 10-digit pnr number/i);
    expect(mockedBookingApi.getBookingByPnr).not.toHaveBeenCalled();

    // 2. Submit short input (5 digits)
    const input = screen.getByLabelText(/enter 10-digit pnr number/i);
    fireEvent.change(input, { target: { value: '12345' } });
    fireEvent.click(submitBtn);
    expect(await screen.findByRole('alert')).toHaveTextContent(/pnr must be exactly 10 digits \(currently 5\)/i);
    expect(mockedBookingApi.getBookingByPnr).not.toHaveBeenCalled();
  });

  it('performs lookup with valid 10-digit PNR and renders complete booking details', async () => {
    mockedBookingApi.getBookingByPnr.mockResolvedValueOnce(mockBookingDetail);

    render(
      <MemoryRouter>
        <PnrEnquiry />
      </MemoryRouter>,
    );

    const input = screen.getByLabelText(/enter 10-digit pnr number/i);
    fireEvent.change(input, { target: { value: '8492019482' } });

    const submitBtn = screen.getByRole('button', { name: /check status/i });
    fireEvent.click(submitBtn);

    expect(mockedBookingApi.getBookingByPnr).toHaveBeenCalledWith('8492019482');

    // Verify rendered booking details matching exact backend fields
    expect(await screen.findByText('PNR: 8492019482')).toBeInTheDocument();
    expect(screen.getByText('12601 — Mangalore Mail')).toBeInTheDocument();
    expect(screen.getByText('CONFIRMED')).toBeInTheDocument();
    expect(screen.getByText('SUCCESS')).toBeInTheDocument();
    expect(screen.getByText('MAS')).toBeInTheDocument();
    expect(screen.getByText('Chennai Central')).toBeInTheDocument();
    expect(screen.getByText('SBC')).toBeInTheDocument();
    expect(screen.getByText('Bangalore City')).toBeInTheDocument();
    expect(screen.getByText('2026-10-27')).toBeInTheDocument();
    expect(screen.getByText('Dep: 20:15:00')).toBeInTheDocument();
    expect(screen.getByText('Arr: 04:30:00')).toBeInTheDocument();
    expect(screen.getByText('B1 / #24')).toBeInTheDocument();
    expect(screen.getByText(/LOWER \(AC_3_TIER\)/)).toBeInTheDocument();
    expect(screen.getByText('passenger_arun')).toBeInTheDocument();
    expect(screen.getByText('₹500.00')).toBeInTheDocument();
    expect(screen.getByText('BKG-20261026-MAS01')).toBeInTheDocument();
  });

  it('displays loading state while request is in flight', async () => {
    let resolvePromise!: (val: BookingDetailResponseDTO) => void;
    mockedBookingApi.getBookingByPnr.mockReturnValueOnce(
      new Promise((res) => {
        resolvePromise = res;
      }),
    );

    render(
      <MemoryRouter>
        <PnrEnquiry />
      </MemoryRouter>,
    );

    const input = screen.getByLabelText(/enter 10-digit pnr number/i);
    fireEvent.change(input, { target: { value: '8492019482' } });

    const submitBtn = screen.getByRole('button', { name: /check status/i });
    fireEvent.click(submitBtn);

    expect(screen.getByText(/fetching pnr reservation details/i)).toBeInTheDocument();
    expect(submitBtn).toBeDisabled();

    // Resolve request
    resolvePromise(mockBookingDetail);
    expect(await screen.findByText('PNR: 8492019482')).toBeInTheDocument();
    expect(screen.queryByText(/fetching pnr reservation details/i)).not.toBeInTheDocument();
  });

  it('handles HTTP 404 PNR not found and enables retry', async () => {
    mockedBookingApi.getBookingByPnr.mockRejectedValueOnce({
      response: {
        status: 404,
        data: { message: 'Booking not found for PNR: 9999999999' },
      },
    });

    render(
      <MemoryRouter>
        <PnrEnquiry />
      </MemoryRouter>,
    );

    const input = screen.getByLabelText(/enter 10-digit pnr number/i);
    fireEvent.change(input, { target: { value: '9999999999' } });
    fireEvent.click(screen.getByRole('button', { name: /check status/i }));

    expect(await screen.findByText('PNR Not Found')).toBeInTheDocument();
    expect(screen.getByText('Booking not found for PNR: 9999999999')).toBeInTheDocument();

    // Click Try Again
    mockedBookingApi.getBookingByPnr.mockResolvedValueOnce({
      ...mockBookingDetail,
      pnr: '9999999999',
    });

    const retryBtn = screen.getByRole('button', { name: /try again/i });
    fireEvent.click(retryBtn);

    expect(mockedBookingApi.getBookingByPnr).toHaveBeenCalledTimes(2);
    expect(await screen.findByText('PNR: 9999999999')).toBeInTheDocument();
  });

  it('handles HTTP 401 unauthorized with sign in link', async () => {
    mockedBookingApi.getBookingByPnr.mockRejectedValueOnce({
      response: {
        status: 401,
      },
    });

    render(
      <MemoryRouter>
        <PnrEnquiry />
      </MemoryRouter>,
    );

    const input = screen.getByLabelText(/enter 10-digit pnr number/i);
    fireEvent.change(input, { target: { value: '8492019482' } });
    fireEvent.click(screen.getByRole('button', { name: /check status/i }));

    expect(await screen.findByText('Authentication Required')).toBeInTheDocument();
    expect(screen.getByText(/please sign in to check your booking pnr status/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /sign in to view/i })).toHaveAttribute('href', '/login');
  });

  it('handles HTTP 403 access restricted', async () => {
    mockedBookingApi.getBookingByPnr.mockRejectedValueOnce({
      response: {
        status: 403,
        data: { message: 'You are not authorized to view this booking' },
      },
    });

    render(
      <MemoryRouter>
        <PnrEnquiry />
      </MemoryRouter>,
    );

    const input = screen.getByLabelText(/enter 10-digit pnr number/i);
    fireEvent.change(input, { target: { value: '8492019482' } });
    fireEvent.click(screen.getByRole('button', { name: /check status/i }));

    expect(await screen.findByText('Access Restricted')).toBeInTheDocument();
    expect(screen.getByText('You are not authorized to view this booking')).toBeInTheDocument();
  });

  it('handles generic server errors and allows clearing', async () => {
    mockedBookingApi.getBookingByPnr.mockRejectedValueOnce(new Error('Network error'));

    render(
      <MemoryRouter>
        <PnrEnquiry />
      </MemoryRouter>,
    );

    const input = screen.getByLabelText(/enter 10-digit pnr number/i);
    fireEvent.change(input, { target: { value: '8492019482' } });
    fireEvent.click(screen.getByRole('button', { name: /check status/i }));

    expect(await screen.findByText('Unable to Load PNR')).toBeInTheDocument();

    // Click Search Another PNR
    const clearBtn = screen.getByRole('button', { name: /search another pnr/i });
    fireEvent.click(clearBtn);

    expect(screen.queryByText('Unable to Load PNR')).not.toBeInTheDocument();
    expect(screen.getByLabelText(/enter 10-digit pnr number/i)).toHaveValue('');
    expect(screen.getByText(/where can i find my pnr\?/i)).toBeInTheDocument();
  });

  it('auto-searches when valid initialPnr prop is passed', async () => {
    mockedBookingApi.getBookingByPnr.mockResolvedValueOnce(mockBookingDetail);

    render(
      <MemoryRouter>
        <PnrEnquiry initialPnr="8492019482" />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(mockedBookingApi.getBookingByPnr).toHaveBeenCalledWith('8492019482');
    });
    expect(await screen.findByText('PNR: 8492019482')).toBeInTheDocument();
  });
});
