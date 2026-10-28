import apiClient from '@/lib/api-client';
import { API_PATHS } from '@/lib/constants';
import type {
  BookingDetailResponseDTO,
  BookingResponseDTO,
  CreateBookingRequestDTO,
  HoldSeatRequestDTO,
  JourneyDTO,
  JourneyStopDTO,
  PageResponseDTO,
  PaymentRequestDTO,
  PaymentResponseDTO,
  SeatAvailabilityDTO,
} from './types';

export const bookingApi = {
  /**
   * Search or list journeys, optionally filtered by trainId and date.
   */
  async getJourneys(params?: { trainId?: number; date?: string }): Promise<JourneyDTO[]> {
    const response = await apiClient.get<JourneyDTO[]>(API_PATHS.JOURNEYS.LIST, { params });
    return response.data;
  },

  /**
   * Get stops along a scheduled journey route.
   */
  async getJourneyStops(journeyId: number): Promise<JourneyStopDTO[]> {
    const response = await apiClient.get<JourneyStopDTO[]>(API_PATHS.JOURNEYS.STOPS(journeyId));
    return response.data;
  },

  /**
   * Get seat availability for a journey and optional travel segment.
   */
  async getJourneySeats(
    journeyId: number,
    boardingStopId?: number,
    alightingStopId?: number,
  ): Promise<SeatAvailabilityDTO[]> {
    const response = await apiClient.get<SeatAvailabilityDTO[]>(API_PATHS.JOURNEYS.SEATS(journeyId), {
      params: {
        boardingJourneyStopId: boardingStopId,
        alightingJourneyStopId: alightingStopId,
      },
    });
    return response.data;
  },

  /**
   * Place an authenticated hold on a seat for a journey segment.
   * Attaches X-Tatkal-Admission header when an admission token/pass is provided.
   */
  async holdSeat(
    journeyId: number,
    request: HoldSeatRequestDTO,
    admissionPass?: string,
  ): Promise<SeatAvailabilityDTO> {
    const response = admissionPass
      ? await apiClient.post<SeatAvailabilityDTO>(
          API_PATHS.JOURNEYS.HOLD(journeyId),
          request,
          { headers: { 'X-Tatkal-Admission': admissionPass } },
        )
      : await apiClient.post<SeatAvailabilityDTO>(
          API_PATHS.JOURNEYS.HOLD(journeyId),
          request,
        );
    return response.data;
  },

  /**
   * Create an authenticated booking from an active seat hold.
   */
  async createBooking(request: CreateBookingRequestDTO): Promise<BookingResponseDTO> {
    const response = await apiClient.post<BookingResponseDTO>(API_PATHS.BOOKINGS.CREATE, request);
    return response.data;
  },

  /**
   * Pay and confirm a pending booking.
   */
  async payBooking(
    bookingRef: string,
    request: PaymentRequestDTO = { paymentMethod: 'MOCK_UPI', simulateFailure: false },
  ): Promise<PaymentResponseDTO> {
    const response = await apiClient.post<PaymentResponseDTO>(
      API_PATHS.BOOKINGS.PAY(bookingRef),
      request,
    );
    return response.data;
  },

  /**
   * Fetch single booking details by unique reference.
   */
  async getBookingByRef(bookingRef: string): Promise<BookingDetailResponseDTO> {
    const response = await apiClient.get<BookingDetailResponseDTO>(
      API_PATHS.BOOKINGS.BY_REF(bookingRef),
    );
    return response.data;
  },

  /**
   * Fetch paginated booking history for authenticated user.
   */
  async getBookingHistory(
    page: number = 0,
    size: number = 10,
  ): Promise<PageResponseDTO<BookingDetailResponseDTO>> {
    const response = await apiClient.get<PageResponseDTO<BookingDetailResponseDTO>>(
      API_PATHS.BOOKINGS.LIST,
      { params: { page, size } },
    );
    return response.data;
  },

  /**
   * Cancel an eligible booking (confirmed or pending payment).
   */
  async cancelBooking(bookingRef: string): Promise<BookingDetailResponseDTO> {
    const response = await apiClient.post<BookingDetailResponseDTO>(
      API_PATHS.BOOKINGS.CANCEL(bookingRef),
    );
    return response.data;
  },
};
