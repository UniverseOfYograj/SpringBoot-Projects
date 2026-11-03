/**
 * Booking types strictly aligned with backend Spring Boot contracts:
 * - in.yograj.learnspringboot.railway_booking.dto.SeatAvailabilityResponseDTO
 * - in.yograj.learnspringboot.railway_booking.dto.HoldSeatRequestDTO
 * - in.yograj.learnspringboot.railway_booking.dto.CreateBookingRequestDTO
 * - in.yograj.learnspringboot.railway_booking.dto.BookingResponseDTO
 * - in.yograj.learnspringboot.railway_booking.dto.BookingDetailResponseDTO
 * - in.yograj.learnspringboot.railway_booking.dto.PaymentRequestDTO
 * - in.yograj.learnspringboot.railway_booking.dto.PaymentResponseDTO
 * - in.yograj.learnspringboot.railway_booking.dto.PageResponseDTO
 */

export interface SeatAvailabilityDTO {
  inventoryId: number;
  journeyId: number;
  coachId: number;
  coachNumber: string;
  seatId: number;
  seatNumber: number;
  seatType: string;
  status: 'AVAILABLE' | 'HOLD' | 'BOOKED' | string;
  holdExpiresAt: string | null;
  updatedAt: string | null;
  seatAllocationId: number | null;
  boardingJourneyStopId: number | null;
  alightingJourneyStopId: number | null;
}

export interface HoldSeatRequestDTO {
  seatId: number;
  holdMinutes: number;
  boardingJourneyStopId?: number;
  alightingJourneyStopId?: number;
  bookingMode?: 'GENERAL' | 'TATKAL';
}

export interface CreateBookingRequestDTO {
  journeyId: number;
  seatId: number;
  boardingJourneyStopId?: number;
  alightingJourneyStopId?: number;
  bookingMode?: 'GENERAL' | 'TATKAL';
}

export interface BookingResponseDTO {
  bookingId: number;
  bookingRef: string;
  pnr?: string | null;
  journeyId: number;
  seatId: number;
  coachNumber: string;
  seatNumber: number;
  bookingStatus: 'PENDING_PAYMENT' | 'CONFIRMED' | 'CANCELLED' | string;
  paymentStatus: 'PENDING' | 'SUCCESS' | 'FAILED' | string;
  amount: number | null;
  createdAt: string;
}

export interface BookingDetailResponseDTO {
  bookingId: number;
  bookingRef: string;
  pnr?: string | null;
  userId: number;
  username: string;
  journeyId: number;
  trainNumber: string;
  trainName: string;
  fromStationCode: string;
  fromStationName: string;
  toStationCode: string;
  toStationName: string;
  journeyDate: string;
  departureTime: string;
  arrivalTime: string;
  coachId: number;
  coachNumber: string;
  coachType: string;
  seatId: number;
  seatNumber: number;
  seatType: string;
  seatAllocationId: number;
  bookingStatus: 'PENDING_PAYMENT' | 'CONFIRMED' | 'CANCELLED' | string;
  paymentStatus: 'PENDING' | 'SUCCESS' | 'FAILED' | string;
  amount: number | null;
  createdAt: string;
}

export interface PaymentRequestDTO {
  paymentMethod?: string;
  idempotencyKey?: string;
  simulateFailure?: boolean;
}

export interface PaymentResponseDTO {
  transactionRef: string;
  bookingRef: string;
  bookingId: number;
  amount: number;
  paymentStatus: 'SUCCESS' | 'FAILED' | string;
  bookingStatus: 'CONFIRMED' | 'PENDING_PAYMENT' | 'CANCELLED' | string;
  paymentMethod: string;
  message: string;
  transactionTime: string;
}

export interface SimulatedPaymentSummaryDTO {
  bookingRef: string;
  bookingId: number;
  pnr: string | null;
  journeyId: number | null;
  trainNumber: string | null;
  trainName: string | null;
  fromStationCode: string | null;
  fromStationName: string | null;
  toStationCode: string | null;
  toStationName: string | null;
  journeyDate: string | null;
  departureTime: string | null;
  arrivalTime: string | null;
  coachNumber: string | null;
  coachType: string | null;
  seatNumber: number | null;
  seatType: string | null;
  amount: number | null;
  bookingStatus: string;
  paymentStatus: string;
  holdExpiresAt: string | null;
  mode: string;
}

export interface SimulatedPaymentRequestDTO {
  idempotencyKey?: string;
  reason?: string;
}

export interface RazorpayOrderResponseDTO {
  orderId: string;
  keyId: string;
  bookingRef: string;
  amount: number;
  currency: 'INR';
  transactionRef: string;
}

export interface RazorpayCheckoutResponse {
  razorpay_order_id: string;
  razorpay_payment_id: string;
  razorpay_signature: string;
}

export interface RazorpayPaymentStatusDTO {
  transactionRef: string;
  bookingRef: string;
  paymentStatus: string;
  bookingStatus: string;
  message: string;
}

export interface PageResponseDTO<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
  hasNext: boolean;
  hasPrevious: boolean;
}

export interface JourneyDTO {
  journeyId: number;
  trainId: number;
  trainNumber: string;
  trainName: string;
  fromStationId: number;
  fromStationCode: string;
  fromStationName: string;
  toStationId: number;
  toStationCode: string;
  toStationName: string;
  journeyDate: string;
  departureTime: string;
  arrivalTime: string;
  trainRouteId: number | null;
  departureAt: string | null;
  arrivalAt: string | null;
}

export interface JourneyStopDTO {
  journeyStopId: number;
  stationId: number;
  stationCode: string;
  stationName: string;
  stopSequence: number;
  arrivalAt: string | null;
  departureAt: string | null;
}
