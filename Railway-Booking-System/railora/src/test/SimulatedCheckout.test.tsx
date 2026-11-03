import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { SimulatedCheckoutModal } from '@/features/booking/components/SimulatedCheckoutModal';
import { bookingApi } from '@/features/booking/api';
import type { PaymentResponseDTO, SimulatedPaymentSummaryDTO } from '@/features/booking/types';

vi.mock('@/features/booking/api', () => ({
  bookingApi: {
    getSimulatedPaymentSummary: vi.fn(),
    initiateSimulatedPayment: vi.fn(),
    simulatePaymentSuccess: vi.fn(),
    simulatePaymentFailure: vi.fn(),
  },
}));

const mockedBookingApi = vi.mocked(bookingApi);

const mockSummary: SimulatedPaymentSummaryDTO = {
  bookingRef: 'RB-TEST-SIM-001',
  bookingId: 42,
  pnr: '8492019482',
  journeyId: 10,
  trainNumber: '12601',
  trainName: 'Mangalore Mail',
  fromStationCode: 'MAS',
  fromStationName: 'Chennai Central',
  toStationCode: 'SBC',
  toStationName: 'Bangalore City',
  journeyDate: '2026-11-10',
  departureTime: '20:15:00',
  arrivalTime: '04:30:00',
  coachNumber: 'B1',
  coachType: '3A',
  seatNumber: 12,
  seatType: 'LOWER',
  amount: 500.0,
  bookingStatus: 'PENDING_PAYMENT',
  paymentStatus: 'PENDING',
  holdExpiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
  mode: 'SIMULATED',
};

describe('SimulatedCheckoutModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders demo mode notice, journey summary, and persisted fare', async () => {
    mockedBookingApi.getSimulatedPaymentSummary.mockResolvedValueOnce(mockSummary);

    render(
      <MemoryRouter>
        <SimulatedCheckoutModal
          isOpen={true}
          onClose={vi.fn()}
          bookingRef="RB-TEST-SIM-001"
        />
      </MemoryRouter>,
    );

    // Verify Demo Notice
    expect(await screen.findByText(/DEMO MODE — NO REAL MONEY WILL BE CHARGED/i)).toBeInTheDocument();
    expect(screen.getByText(/simulation of the payment outcome/i)).toBeInTheDocument();

    // Verify Train and Seat Details
    expect(screen.getByText('12601 — Mangalore Mail')).toBeInTheDocument();
    expect(screen.getByText(/Coach B1, Seat #12/i)).toBeInTheDocument();
    expect(screen.getByText(/RB-TEST-SIM-001/i)).toBeInTheDocument();

    // Verify Persisted Fare
    expect(screen.getByText('₹500.00')).toBeInTheDocument();
    expect(screen.getByText('Persisted Booking Fare:')).toBeInTheDocument();

    // Verify the Two Clearly Separated Actions
    expect(screen.getByRole('button', { name: /simulate successful payment/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /simulate failed payment/i })).toBeInTheDocument();
  });

  it('handles simulated successful payment and displays confirmation screen with PNR', async () => {
    mockedBookingApi.getSimulatedPaymentSummary.mockResolvedValueOnce(mockSummary);

    const successResponse: PaymentResponseDTO = {
      transactionRef: 'SIM-TXN-SUCCESS-777',
      bookingRef: 'RB-TEST-SIM-001',
      bookingId: 42,
      amount: 500.0,
      paymentStatus: 'SUCCESS',
      bookingStatus: 'CONFIRMED',
      paymentMethod: 'SIMULATED',
      message: 'Simulated payment successful and booking confirmed',
      transactionTime: new Date().toISOString(),
    };
    mockedBookingApi.simulatePaymentSuccess.mockResolvedValueOnce(successResponse);

    const onSuccess = vi.fn();

    render(
      <MemoryRouter>
        <SimulatedCheckoutModal
          isOpen={true}
          onClose={vi.fn()}
          bookingRef="RB-TEST-SIM-001"
          onSuccess={onSuccess}
        />
      </MemoryRouter>,
    );

    const successBtn = await screen.findByRole('button', { name: /simulate successful payment/i });
    fireEvent.click(successBtn);

    // Verify success API call with an idempotency key
    await waitFor(() => {
      expect(mockedBookingApi.simulatePaymentSuccess).toHaveBeenCalledWith(
        'RB-TEST-SIM-001',
        expect.objectContaining({
          idempotencyKey: expect.stringMatching(/^SIM-/),
        }),
      );
    });

    // Verify Success Screen
    expect(await screen.findByRole('heading', { name: /reservation confirmed!/i })).toBeInTheDocument();
    expect(screen.getByText('8492019482')).toBeInTheDocument(); // PNR
    expect(screen.getByText('SIM-TXN-SUCCESS-777')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /view confirmed booking/i })).toBeInTheDocument();

    expect(onSuccess).toHaveBeenCalledWith(successResponse);
  });

  it('handles simulated failed payment and allows retrying with a new idempotency key', async () => {
    mockedBookingApi.getSimulatedPaymentSummary.mockResolvedValueOnce(mockSummary);

    const failureResponse: PaymentResponseDTO = {
      transactionRef: 'SIM-TXN-FAIL-888',
      bookingRef: 'RB-TEST-SIM-001',
      bookingId: 42,
      amount: 500.0,
      paymentStatus: 'FAILED',
      bookingStatus: 'PENDING_PAYMENT',
      paymentMethod: 'SIMULATED',
      message: 'Simulated payment failure: Card declined',
      transactionTime: new Date().toISOString(),
    };
    mockedBookingApi.simulatePaymentFailure.mockResolvedValueOnce(failureResponse);

    const successResponse: PaymentResponseDTO = {
      transactionRef: 'SIM-TXN-RETRY-SUCCESS-999',
      bookingRef: 'RB-TEST-SIM-001',
      bookingId: 42,
      amount: 500.0,
      paymentStatus: 'SUCCESS',
      bookingStatus: 'CONFIRMED',
      paymentMethod: 'SIMULATED',
      message: 'Simulated payment successful and booking confirmed',
      transactionTime: new Date().toISOString(),
    };
    mockedBookingApi.simulatePaymentSuccess.mockResolvedValueOnce(successResponse);

    render(
      <MemoryRouter>
        <SimulatedCheckoutModal
          isOpen={true}
          onClose={vi.fn()}
          bookingRef="RB-TEST-SIM-001"
        />
      </MemoryRouter>,
    );

    const failBtn = await screen.findByRole('button', { name: /simulate failed payment/i });
    fireEvent.click(failBtn);

    // Verify failure API call
    await waitFor(() => {
      expect(mockedBookingApi.simulatePaymentFailure).toHaveBeenCalledWith(
        'RB-TEST-SIM-001',
        expect.objectContaining({
          idempotencyKey: expect.stringMatching(/^SIM-/),
        }),
      );
    });

    const firstCallKey = mockedBookingApi.simulatePaymentFailure.mock.calls[0][1].idempotencyKey;

    // Verify Failure Screen & Retry button
    expect(await screen.findByRole('heading', { name: /simulated payment declined/i })).toBeInTheDocument();
    expect(screen.getByText('SIM-TXN-FAIL-888')).toBeInTheDocument();
    const retryBtn = screen.getByRole('button', { name: /retry payment/i });
    expect(retryBtn).toBeInTheDocument();

    // Click Retry
    fireEvent.click(retryBtn);

    // Verify screen is reset to summary with actions
    expect(await screen.findByRole('button', { name: /simulate successful payment/i })).toBeInTheDocument();

    // Now click simulate success on retry
    fireEvent.click(screen.getByRole('button', { name: /simulate successful payment/i }));

    await waitFor(() => {
      expect(mockedBookingApi.simulatePaymentSuccess).toHaveBeenCalled();
    });

    const secondCallKey = mockedBookingApi.simulatePaymentSuccess.mock.calls[0][1].idempotencyKey;

    // Verify a NEW idempotency key was generated for retry attempt
    expect(secondCallKey).not.toBe(firstCallKey);

    // Verify final success screen
    expect(await screen.findByRole('heading', { name: /reservation confirmed!/i })).toBeInTheDocument();
  });
});
