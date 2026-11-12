import apiClient from '@/lib/api-client';
import { API_PATHS } from '@/lib/constants';
import type {
  BatchHoldSeatRequestDTO,
  BookingDetailResponseDTO,
  BookingResponseDTO,
  CreateBookingRequestDTO,
  FareQuoteRequestDTO,
  FareQuoteResponseDTO,
  HoldSeatRequestDTO,
  JourneyDTO,
  JourneyStopDTO,
  PageResponseDTO,
  PaymentRequestDTO,
  PaymentResponseDTO,
  RazorpayOrderResponseDTO,
  RazorpayCheckoutResponse,
  RazorpayPaymentStatusDTO,
  SeatAvailabilityDTO,
  SimulatedPaymentRequestDTO,
  SimulatedPaymentSummaryDTO,
  TravelClassAvailabilityDTO,
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
   * Get seat availability for a journey and optional travel segment, optionally filtered by travel class and coach.
   */
  async getJourneySeats(
    journeyId: number,
    boardingStopId?: number,
    alightingStopId?: number,
    travelClass?: string,
    coachNumber?: string,
  ): Promise<SeatAvailabilityDTO[]> {
    const response = await apiClient.get<SeatAvailabilityDTO[]>(API_PATHS.JOURNEYS.SEATS(journeyId), {
      params: {
        boardingJourneyStopId: boardingStopId,
        alightingJourneyStopId: alightingStopId,
        travelClass,
        coachNumber,
      },
    });
    return response.data;
  },

  /**
   * Get class-grouped coach and seat availability for a journey and optional travel segment.
   */
  async getJourneyClasses(
    journeyId: number,
    boardingStopId?: number,
    alightingStopId?: number,
  ): Promise<TravelClassAvailabilityDTO[]> {
    const response = await apiClient.get<TravelClassAvailabilityDTO[]>(
      API_PATHS.JOURNEYS.CLASSES(journeyId),
      {
        params: {
          boardingJourneyStopId: boardingStopId,
          alightingJourneyStopId: alightingStopId,
        },
      },
    );
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
   * Place an authenticated batch hold on multiple seats for a journey segment.
   * Attaches X-Tatkal-Admission header when an admission token/pass is provided.
   */
  async holdSeats(
    journeyId: number,
    request: BatchHoldSeatRequestDTO,
    admissionPass?: string,
  ): Promise<SeatAvailabilityDTO[]> {
    const response = admissionPass
      ? await apiClient.post<SeatAvailabilityDTO[]>(
          API_PATHS.JOURNEYS.HOLD_BATCH(journeyId),
          request,
          { headers: { 'X-Tatkal-Admission': admissionPass } },
        )
      : await apiClient.post<SeatAvailabilityDTO[]>(
          API_PATHS.JOURNEYS.HOLD_BATCH(journeyId),
          request,
        );
    return response.data;
  },

  /**
   * Create an authenticated booking from an active seat hold.
   * Attaches X-Tatkal-Admission header when an admission token/pass is provided.
   */
  async createBooking(
    request: CreateBookingRequestDTO,
    admissionPass?: string,
  ): Promise<BookingResponseDTO> {
    const response = admissionPass
      ? await apiClient.post<BookingResponseDTO>(
          API_PATHS.BOOKINGS.CREATE,
          request,
          { headers: { 'X-Tatkal-Admission': admissionPass } },
        )
      : await apiClient.post<BookingResponseDTO>(
          API_PATHS.BOOKINGS.CREATE,
          request,
        );
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
   * Get simulated payment summary for a pending booking.
   */
  async getSimulatedPaymentSummary(bookingRef: string): Promise<SimulatedPaymentSummaryDTO> {
    const response = await apiClient.get<SimulatedPaymentSummaryDTO>(
      API_PATHS.BOOKINGS.SIMULATED_SUMMARY(bookingRef),
    );
    return response.data;
  },

  /**
   * Initiate simulated checkout and validate booking state.
   */
  async initiateSimulatedPayment(bookingRef: string): Promise<SimulatedPaymentSummaryDTO> {
    const response = await apiClient.post<SimulatedPaymentSummaryDTO>(
      API_PATHS.BOOKINGS.SIMULATED_INITIATE(bookingRef),
    );
    return response.data;
  },

  /**
   * Simulate successful payment processing and booking confirmation.
   */
  async simulatePaymentSuccess(
    bookingRef: string,
    request: SimulatedPaymentRequestDTO,
  ): Promise<PaymentResponseDTO> {
    const response = await apiClient.post<PaymentResponseDTO>(
      API_PATHS.BOOKINGS.SIMULATED_SUCCESS(bookingRef),
      request,
    );
    return response.data;
  },

  /**
   * Simulate failed payment processing (preserving booking hold for retry).
   */
  async simulatePaymentFailure(
    bookingRef: string,
    request: SimulatedPaymentRequestDTO,
  ): Promise<PaymentResponseDTO> {
    const response = await apiClient.post<PaymentResponseDTO>(
      API_PATHS.BOOKINGS.SIMULATED_FAILURE(bookingRef),
      request,
    );
    return response.data;
  },

  async createRazorpayOrder(bookingRef: string): Promise<RazorpayOrderResponseDTO> {
    const response = await apiClient.post<RazorpayOrderResponseDTO>(
      API_PATHS.BOOKINGS.RAZORPAY_ORDER(bookingRef),
    );
    return response.data;
  },

  async verifyRazorpayPayment(
    bookingRef: string,
    checkout: RazorpayCheckoutResponse,
  ): Promise<RazorpayPaymentStatusDTO> {
    const response = await apiClient.post<RazorpayPaymentStatusDTO>(
      API_PATHS.BOOKINGS.RAZORPAY_VERIFY(bookingRef),
      {
        orderId: checkout.razorpay_order_id,
        paymentId: checkout.razorpay_payment_id,
        signature: checkout.razorpay_signature,
      },
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
   * Fetch single booking details by 10-digit numeric PNR.
   */
  async getBookingByPnr(pnr: string): Promise<BookingDetailResponseDTO> {
    const response = await apiClient.get<BookingDetailResponseDTO>(
      API_PATHS.BOOKINGS.BY_PNR(pnr),
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
   * Get dynamic fare calculation quote for a journey, class, and passenger count.
   */
  async getFareQuote(params: FareQuoteRequestDTO): Promise<FareQuoteResponseDTO> {
    const response = await apiClient.get<FareQuoteResponseDTO>(API_PATHS.FARE.QUOTE, { params });
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
