import type { AxiosResponse } from 'axios';
import { afterEach, describe, expect, it, vi } from 'vitest';

const { mockGet, mockPost } = vi.hoisted(() => ({
  mockGet: vi.fn(),
  mockPost: vi.fn(),
}));

vi.mock('@/lib/api-client', () => ({
  default: {
    get: mockGet,
    post: mockPost,
  },
}));

import { bookingApi } from '@/features/booking/api';

function response<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

afterEach(() => {
  vi.clearAllMocks();
});

describe('booking API client', () => {
  it('calls getJourneys with optional trainId and date filters', async () => {
    const mockJourneys = [
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
    ];
    mockGet.mockResolvedValueOnce(response(mockJourneys));

    const result = await bookingApi.getJourneys({ trainId: 1, date: '2026-10-27' });
    expect(result).toEqual(mockJourneys);
    expect(mockGet).toHaveBeenCalledWith('/journeys', {
      params: { trainId: 1, date: '2026-10-27' },
    });
  });

  it('calls getJourneyStops with journey ID', async () => {
    const mockStops = [
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
    mockGet.mockResolvedValueOnce(response(mockStops));

    const result = await bookingApi.getJourneyStops(10);
    expect(result).toEqual(mockStops);
    expect(mockGet).toHaveBeenCalledWith('/journeys/10/stops');
  });

  it('calls getJourneySeats with segment filters', async () => {
    const mockSeats = [
      {
        inventoryId: 1,
        journeyId: 10,
        coachId: 1,
        coachNumber: 'B1',
        seatId: 101,
        seatNumber: 12,
        seatType: 'LOWER',
        status: 'AVAILABLE',
        holdExpiresAt: null,
        updatedAt: '2026-10-26T12:00:00',
        seatAllocationId: null,
        boardingJourneyStopId: 101,
        alightingJourneyStopId: 102,
      },
    ];
    mockGet.mockResolvedValueOnce(response(mockSeats));

    const result = await bookingApi.getJourneySeats(10, 101, 102);
    expect(result).toEqual(mockSeats);
    expect(mockGet).toHaveBeenCalledWith('/journeys/10/seats', {
      params: {
        boardingJourneyStopId: 101,
        alightingJourneyStopId: 102,
        travelClass: undefined,
        coachNumber: undefined,
      },
    });
  });

  it('calls getJourneySeats with travelClass and coachNumber filters', async () => {
    mockGet.mockResolvedValueOnce(response([]));
    await bookingApi.getJourneySeats(10, 101, 102, '3A', 'B1');
    expect(mockGet).toHaveBeenCalledWith('/journeys/10/seats', {
      params: {
        boardingJourneyStopId: 101,
        alightingJourneyStopId: 102,
        travelClass: '3A',
        coachNumber: 'B1',
      },
    });
  });

  it('calls getJourneyClasses with segment filters', async () => {
    const mockClasses = [
      {
        classCode: '3A',
        className: 'AC 3 Tier',
        totalSeats: 6,
        availableSeats: 6,
        heldSeats: 0,
        bookedSeats: 0,
        baseFare: 1000.0,
        coaches: [],
      },
    ];
    mockGet.mockResolvedValueOnce(response(mockClasses));

    const result = await bookingApi.getJourneyClasses(10, 101, 102);
    expect(result).toEqual(mockClasses);
    expect(mockGet).toHaveBeenCalledWith('/journeys/10/classes', {
      params: { boardingJourneyStopId: 101, alightingJourneyStopId: 102 },
    });
  });

  it('sends holdSeat request and returns held seat availability', async () => {
    const mockHeldSeat = {
      inventoryId: 1,
      journeyId: 10,
      coachId: 1,
      coachNumber: 'B1',
      seatId: 101,
      seatNumber: 12,
      seatType: 'LOWER',
      status: 'HOLD',
      holdExpiresAt: '2026-10-26T12:10:00',
      updatedAt: '2026-10-26T12:00:00',
      seatAllocationId: 50,
      boardingJourneyStopId: 101,
      alightingJourneyStopId: 102,
    };
    mockPost.mockResolvedValueOnce(response(mockHeldSeat));

    const result = await bookingApi.holdSeat(10, {
      seatId: 101,
      holdMinutes: 10,
      boardingJourneyStopId: 101,
      alightingJourneyStopId: 102,
    });
    expect(result).toEqual(mockHeldSeat);
    expect(mockPost).toHaveBeenCalledWith('/journeys/10/seats/hold', {
      seatId: 101,
      holdMinutes: 10,
      boardingJourneyStopId: 101,
      alightingJourneyStopId: 102,
    });
  });

  it('sends holdSeat request with X-Tatkal-Admission header and TATKAL bookingMode when pass provided', async () => {
    const mockHeldSeat = {
      inventoryId: 2,
      journeyId: 10,
      coachId: 1,
      coachNumber: 'B1',
      seatId: 102,
      seatNumber: 14,
      seatType: 'LOWER',
      status: 'HOLD',
      holdExpiresAt: '2026-10-26T12:10:00',
      updatedAt: '2026-10-26T12:00:00',
      seatAllocationId: 51,
      boardingJourneyStopId: 101,
      alightingJourneyStopId: 102,
    };
    mockPost.mockResolvedValueOnce(response(mockHeldSeat));

    const result = await bookingApi.holdSeat(
      10,
      {
        seatId: 102,
        holdMinutes: 10,
        boardingJourneyStopId: 101,
        alightingJourneyStopId: 102,
        bookingMode: 'TATKAL',
      },
      'tatkal-pass-token-abc-123',
    );
    expect(result).toEqual(mockHeldSeat);
    expect(mockPost).toHaveBeenCalledWith(
      '/journeys/10/seats/hold',
      {
        seatId: 102,
        holdMinutes: 10,
        boardingJourneyStopId: 101,
        alightingJourneyStopId: 102,
        bookingMode: 'TATKAL',
      },
      {
        headers: {
          'X-Tatkal-Admission': 'tatkal-pass-token-abc-123',
        },
      },
    );
  });

  it('sends holdSeats batch request and returns held seats availability', async () => {
    const mockHeldSeats = [
      {
        inventoryId: 1,
        journeyId: 10,
        coachId: 1,
        coachNumber: 'B1',
        seatId: 101,
        seatNumber: 12,
        seatType: 'LOWER',
        status: 'HOLD',
        holdExpiresAt: '2026-10-26T12:10:00',
        updatedAt: '2026-10-26T12:00:00',
        seatAllocationId: 50,
        boardingJourneyStopId: 101,
        alightingJourneyStopId: 102,
      },
      {
        inventoryId: 2,
        journeyId: 10,
        coachId: 1,
        coachNumber: 'B1',
        seatId: 102,
        seatNumber: 13,
        seatType: 'UPPER',
        status: 'HOLD',
        holdExpiresAt: '2026-10-26T12:10:00',
        updatedAt: '2026-10-26T12:00:00',
        seatAllocationId: 51,
        boardingJourneyStopId: 101,
        alightingJourneyStopId: 102,
      },
    ];
    mockPost.mockResolvedValueOnce(response(mockHeldSeats));

    const result = await bookingApi.holdSeats(10, {
      seatIds: [101, 102],
      holdMinutes: 10,
      boardingJourneyStopId: 101,
      alightingJourneyStopId: 102,
      travelClass: '3A',
      coachNumber: 'B1',
    });
    expect(result).toEqual(mockHeldSeats);
    expect(mockPost).toHaveBeenCalledWith('/journeys/10/seats/hold-batch', {
      seatIds: [101, 102],
      holdMinutes: 10,
      boardingJourneyStopId: 101,
      alightingJourneyStopId: 102,
      travelClass: '3A',
      coachNumber: 'B1',
    });
  });

  it('sends createBooking request and returns pending booking response', async () => {
    const mockBooking = {
      bookingId: 77,
      bookingRef: 'BKG-20261026-0001',
      pnr: '8492019482',
      journeyId: 10,
      seatId: 101,
      coachNumber: 'B1',
      seatNumber: 12,
      bookingStatus: 'PENDING_PAYMENT',
      paymentStatus: 'PENDING',
      amount: 500.0,
      createdAt: '2026-10-26T12:00:00',
    };
    mockPost.mockResolvedValueOnce(response(mockBooking));

    const result = await bookingApi.createBooking({
      journeyId: 10,
      seatId: 101,
      boardingJourneyStopId: 101,
      alightingJourneyStopId: 102,
    });
    expect(result).toEqual(mockBooking);
    expect(mockPost).toHaveBeenCalledWith('/bookings', {
      journeyId: 10,
      seatId: 101,
      boardingJourneyStopId: 101,
      alightingJourneyStopId: 102,
    });
  });

  it('sends payBooking request and returns payment response', async () => {
    const mockPayment = {
      transactionRef: 'TXN-999',
      bookingRef: 'BKG-20261026-0001',
      bookingId: 77,
      amount: 500.0,
      paymentStatus: 'SUCCESS',
      bookingStatus: 'CONFIRMED',
      paymentMethod: 'MOCK_UPI',
      message: 'Payment completed successfully',
      transactionTime: '2026-10-26T12:01:00',
    };
    mockPost.mockResolvedValueOnce(response(mockPayment));

    const result = await bookingApi.payBooking('BKG-20261026-0001', {
      paymentMethod: 'MOCK_UPI',
      simulateFailure: false,
    });
    expect(result).toEqual(mockPayment);
    expect(mockPost).toHaveBeenCalledWith('/bookings/BKG-20261026-0001/pay', {
      paymentMethod: 'MOCK_UPI',
      simulateFailure: false,
    });
  });

  it('fetches booking history with pagination', async () => {
    const mockPage = {
      content: [],
      page: 0,
      size: 10,
      totalElements: 0,
      totalPages: 0,
      first: true,
      last: true,
      hasNext: false,
      hasPrevious: false,
    };
    mockGet.mockResolvedValueOnce(response(mockPage));

    const result = await bookingApi.getBookingHistory(0, 10);
    expect(result).toEqual(mockPage);
    expect(mockGet).toHaveBeenCalledWith('/bookings', {
      params: { page: 0, size: 10 },
    });
  });

  it('fetches single booking details by 10-digit PNR', async () => {
    const mockDetail = {
      bookingId: 77,
      bookingRef: 'BKG-20261026-0001',
      pnr: '8492019482',
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
      seatId: 101,
      seatNumber: 12,
      seatType: 'LOWER',
      seatAllocationId: 701,
      bookingStatus: 'CONFIRMED',
      paymentStatus: 'SUCCESS',
      amount: 500.0,
      createdAt: '2026-10-26T12:00:00',
    };
    mockGet.mockResolvedValueOnce(response(mockDetail));

    const result = await bookingApi.getBookingByPnr('8492019482');
    expect(result).toEqual(mockDetail);
    expect(mockGet).toHaveBeenCalledWith('/bookings/pnr/8492019482');
  });

  it('fetches dynamic fare quote from server', async () => {
    const mockQuote = {
      journeyId: 1,
      travelClass: '3A',
      className: 'AC 3 Tier',
      distanceKm: 1447,
      distanceSource: 'CONFIGURED_CORRIDOR',
      passengerCount: 2,
      perPassengerFare: {
        baseFare: 1664.05,
        reservationCharge: 40.0,
        superfastCharge: 45.0,
        tax: 87.45,
        gstAmount: 87.45,
        total: 1836.5,
      },
      bookingTotal: 3673.0,
      currency: 'INR',
      ratePerKm: 1.15,
    };
    mockGet.mockResolvedValueOnce(response(mockQuote));

    const result = await bookingApi.getFareQuote({
      journeyId: 1,
      travelClass: '3A',
      passengerCount: 2,
    });
    expect(result).toEqual(mockQuote);
    expect(mockGet).toHaveBeenCalledWith('/fare/quote', {
      params: {
        journeyId: 1,
        travelClass: '3A',
        passengerCount: 2,
      },
    });
  });
});
