import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  X,
  Train,
  CheckCircle2,
  AlertCircle,
  CreditCard,
  ShieldCheck,
  Calendar,
  Clock,
  ArrowRight,
  User,
  RefreshCw,
} from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Badge';
import { useAuth } from '@/features/auth/AuthContext';
import { bookingApi } from '../api';
import { clearPaymentAttempt, getOrCreatePaymentAttempt } from '../paymentAttempt';
import type {
  BookingResponseDTO,
  JourneyDTO,
  JourneyStopDTO,
  PaymentResponseDTO,
  SeatAvailabilityDTO,
} from '../types';
import type { TrainSearchResponseDTO } from '@/features/search/types';
import { ROUTES } from '@/lib/constants';

export interface SeatSelectionModalProps {
  isOpen: boolean;
  onClose: () => void;
  train: TrainSearchResponseDTO;
  travelDate?: string;
  journeyId?: number;
  admissionToken?: string;
  quota?: string;
}

type ModalStep = 'select-seat' | 'payment' | 'confirmed';

export function SeatSelectionModal({
  isOpen,
  onClose,
  train,
  travelDate = new Date().toISOString().split('T')[0],
  journeyId,
  admissionToken,
  quota,
}: SeatSelectionModalProps) {
  const { state: authState } = useAuth();
  const navigate = useNavigate();

  const [step, setStep] = useState<ModalStep>('select-seat');
  const [loading, setLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [journey, setJourney] = useState<JourneyDTO | null>(null);
  const [boardingStop, setBoardingStop] = useState<JourneyStopDTO | null>(null);
  const [alightingStop, setAlightingStop] = useState<JourneyStopDTO | null>(null);

  const [seats, setSeats] = useState<SeatAvailabilityDTO[]>([]);
  const [selectedSeat, setSelectedSeat] = useState<SeatAvailabilityDTO | null>(null);
  const [heldSeatId, setHeldSeatId] = useState<number | null>(null);
  const [holdExpiresAt, setHoldExpiresAt] = useState<number | null>(null);
  const [remainingSeconds, setRemainingSeconds] = useState<number | null>(null);

  const [booking, setBooking] = useState<BookingResponseDTO | null>(null);
  const [payment, setPayment] = useState<PaymentResponseDTO | null>(null);
  const [simulateFailure, setSimulateFailure] = useState(false);
  const [paymentAttemptPending, setPaymentAttemptPending] = useState(false);
  const paymentInFlight = useRef(false);

  // Countdown timer for active hold
  useEffect(() => {
    if (!holdExpiresAt) {
      setRemainingSeconds(null);
      return;
    }

    const interval = setInterval(() => {
      const secondsLeft = Math.max(0, Math.floor((holdExpiresAt - Date.now()) / 1000));
      setRemainingSeconds(secondsLeft);
      if (secondsLeft === 0) {
        clearInterval(interval);
        setError('Your seat hold has expired. Please select a seat again.');
        setHeldSeatId(null);
        setHoldExpiresAt(null);
        setBooking(null);
        setStep('select-seat');
      }
    }, 1000);

    return () => clearInterval(interval);
  }, [holdExpiresAt]);

  // Load directional journey, stops, and seat availability when modal opens
  useEffect(() => {
    if (!isOpen) {
      setStep('select-seat');
      setSelectedSeat(null);
      setHeldSeatId(null);
      setHoldExpiresAt(null);
      setBooking(null);
      setPayment(null);
      setError(null);
      setSimulateFailure(false);
      setPaymentAttemptPending(false);
      return;
    }

    let active = true;
    async function loadJourneyData() {
      setLoading(true);
      setError(null);
      try {
        const journeys = await bookingApi.getJourneys({
          trainId: train.trainId,
          date: travelDate,
        });

        if (!active) return;

        if (journeys.length === 0) {
          setError(
            `No scheduled journey run found for ${train.trainNumber} on ${travelDate}. Please choose another date.`,
          );
          setLoading(false);
          return;
        }

        // Find the specific journey run where origin precedes destination
        let matchedJourney: JourneyDTO | null = null;
        let matchedBoarding: JourneyStopDTO | null = null;
        let matchedAlighting: JourneyStopDTO | null = null;

        const candidateList = journeyId
          ? [
              ...journeys.filter((j) => j.journeyId === journeyId),
              ...journeys.filter((j) => j.journeyId !== journeyId),
            ]
          : journeys;

        for (const candidate of candidateList) {
          const candidateStops = await bookingApi.getJourneyStops(candidate.journeyId);
          const bStop = candidateStops.find(
            (s) => s.stationCode.toUpperCase() === train.fromStationCode.toUpperCase(),
          );
          const aStop = candidateStops.find(
            (s) => s.stationCode.toUpperCase() === train.toStationCode.toUpperCase(),
          );
          if (bStop && aStop && bStop.stopSequence < aStop.stopSequence) {
            matchedJourney = candidate;
            matchedBoarding = bStop;
            matchedAlighting = aStop;
            break;
          }
        }

        if (!matchedJourney || !matchedBoarding || !matchedAlighting) {
          setError(
            `No directional journey run matches ${train.fromStationCode} → ${train.toStationCode} on ${travelDate}.`,
          );
          setLoading(false);
          return;
        }

        setJourney(matchedJourney);
        setBoardingStop(matchedBoarding);
        setAlightingStop(matchedAlighting);

        const seatList = await bookingApi.getJourneySeats(
          matchedJourney.journeyId,
          matchedBoarding.journeyStopId,
          matchedAlighting.journeyStopId,
        );
        if (!active) return;
        setSeats(seatList);
      } catch {
        if (!active) return;
        setError('Failed to load seat availability. Please try again.');
      } finally {
        if (active) setLoading(false);
      }
    }

    void loadJourneyData();

    return () => {
      active = false;
    };
  }, [isOpen, train, travelDate, journeyId]);

  if (!isOpen) return null;

  async function handleHoldAndBook() {
    if (authState.status !== 'authenticated') {
      navigate(ROUTES.LOGIN);
      return;
    }

    if (!journey || !selectedSeat || !boardingStop || !alightingStop) return;

    setActionLoading(true);
    setError(null);

    try {
      const isTatkal = quota === 'TATKAL' || Boolean(admissionToken);

      if (quota === 'TATKAL' && !admissionToken) {
        setError(
          'A valid Tatkal waiting room admission token is required to book under the Tatkal quota. Please join the Tatkal queue first.',
        );
        setActionLoading(false);
        return;
      }

      // 1. Hold seat only if not already held for this seat
      const alreadyHeld = heldSeatId === selectedSeat.seatId && holdExpiresAt && holdExpiresAt > Date.now();
      if (!alreadyHeld) {
        await bookingApi.holdSeat(
          journey.journeyId,
          {
            seatId: selectedSeat.seatId,
            holdMinutes: 10,
            boardingJourneyStopId: boardingStop.journeyStopId,
            alightingJourneyStopId: alightingStop.journeyStopId,
            bookingMode: isTatkal ? 'TATKAL' : 'GENERAL',
          },
          admissionToken,
        );
        setHeldSeatId(selectedSeat.seatId);
        setHoldExpiresAt(Date.now() + 10 * 60 * 1000);
      }

      // 2. Create booking
      const newBooking = await bookingApi.createBooking({
        journeyId: journey.journeyId,
        seatId: selectedSeat.seatId,
        boardingJourneyStopId: boardingStop.journeyStopId,
        alightingJourneyStopId: alightingStop.journeyStopId,
        bookingMode: isTatkal ? 'TATKAL' : 'GENERAL',
      });

      setBooking(newBooking);
      setStep('payment');
    } catch (err: unknown) {
      const msg =
        err && typeof err === 'object' && 'response' in err
          ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
          : null;
      setError(msg || 'Could not reserve seat. It may have just been taken by another traveler.');
    } finally {
      setActionLoading(false);
    }
  }

  async function handlePay() {
    if (!booking || paymentInFlight.current) return;

    paymentInFlight.current = true;
    setActionLoading(true);
    setError(null);

    try {
      const attempt = getOrCreatePaymentAttempt(booking.bookingRef, simulateFailure);
      setSimulateFailure(attempt.simulateFailure);
      setPaymentAttemptPending(true);
      const paymentResult = await bookingApi.payBooking(booking.bookingRef, attempt);

      if (paymentResult.paymentStatus === 'FAILED') {
        clearPaymentAttempt(booking.bookingRef);
        setPaymentAttemptPending(false);
        if (paymentResult.bookingStatus === 'PENDING_PAYMENT') {
          setError(
            paymentResult.message ||
              'Payment failed. Your seat hold remains active; please retry payment to confirm.',
          );
        } else {
          setError('This booking can no longer be paid because its seat hold has expired. Please select a seat again.');
          setBooking(null);
          setHeldSeatId(null);
          setHoldExpiresAt(null);
          setStep('select-seat');
        }
        return;
      }

      clearPaymentAttempt(booking.bookingRef);
      setPaymentAttemptPending(false);
      setPayment(paymentResult);
      setStep('confirmed');
    } catch (err: unknown) {
      const msg =
        err && typeof err === 'object' && 'response' in err
          ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
          : null;
      setError(msg || (err instanceof Error ? err.message : null) || 'Payment processing error. Please try again.');
    } finally {
      paymentInFlight.current = false;
      setActionLoading(false);
    }
  }

  function formatTime(seconds: number): string {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6 bg-navy-950/70 backdrop-blur-sm overflow-y-auto"
      role="dialog"
      aria-modal="true"
      aria-labelledby="modal-headline"
    >
      <div className="relative w-full max-w-2xl rounded-2xl bg-white shadow-2xl border border-surface-border overflow-hidden my-8">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-surface-border bg-surface-subtle/40 px-6 py-4">
          <div className="flex items-center gap-2.5">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-navy-700 text-white">
              <Train size={18} />
            </div>
            <div>
              <h2 id="modal-headline" className="text-base sm:text-lg font-bold text-ink-primary">
                {step === 'select-seat' && 'Select Your Seat'}
                {step === 'payment' && 'Confirm & Pay'}
                {step === 'confirmed' && 'Booking Confirmed!'}
              </h2>
              <div className="flex items-center gap-2">
                <p className="text-xs text-ink-muted">
                  {train.trainNumber} {train.trainName} • {train.fromStationCode} → {train.toStationCode}
                </p>
                {admissionToken && (
                  <Badge variant="teal" size="sm">
                    Tatkal Admitted
                  </Badge>
                )}
                {quota === 'TATKAL' && !admissionToken && (
                  <Badge variant="warning" size="sm">
                    Tatkal
                  </Badge>
                )}
              </div>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1.5 text-ink-muted hover:bg-surface-subtle hover:text-ink-primary transition-interact"
            aria-label="Close modal"
          >
            <X size={20} />
          </button>
        </div>

        {/* Modal Body */}
        <div className="p-6">
          {error && (
            <div className="mb-4 flex items-start gap-2.5 rounded-xl border border-status-danger-border bg-status-danger-bg p-3.5 text-sm text-status-danger-text">
              <AlertCircle size={18} className="shrink-0 mt-0.5" />
              <div>{error}</div>
            </div>
          )}

          {remainingSeconds !== null && remainingSeconds > 0 && step !== 'confirmed' && (
            <div className="mb-4 flex items-center justify-between rounded-xl border border-amber-200 bg-amber-50 px-3.5 py-2 text-xs text-amber-800">
              <span className="flex items-center gap-1.5">
                <Clock size={14} className="text-amber-600" />
                Seat hold active for this segment
              </span>
              <span className="font-mono font-bold text-amber-900">
                Expires in {formatTime(remainingSeconds)}
              </span>
            </div>
          )}

          {loading ? (
            <div className="py-12 text-center">
              <div className="mx-auto h-8 w-8 animate-spin rounded-full border-2 border-teal-600 border-t-transparent" />
              <p className="mt-3 text-sm text-ink-muted">Loading journey and seat availability…</p>
            </div>
          ) : step === 'select-seat' ? (
            <div>
              {/* Journey Details Bar */}
              <div className="mb-5 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-surface-border bg-surface-base p-3.5 text-xs text-ink-secondary">
                <span className="flex items-center gap-1.5">
                  <Calendar size={14} className="text-teal-600" />
                  Date: <strong className="text-ink-primary">{travelDate}</strong>
                </span>
                <span className="flex items-center gap-1.5">
                  <Clock size={14} className="text-teal-600" />
                  Departure: <strong className="text-ink-primary">{train.fromDepartureTime ?? 'Scheduled'}</strong>
                </span>
                <span className="flex items-center gap-1.5">
                  <ShieldCheck size={14} className="text-teal-600" />
                  Standard Fare: <strong className="text-ink-primary font-mono text-sm">₹500.00</strong>
                </span>
              </div>

              {/* Seat Legend */}
              <div className="mb-4 flex items-center justify-center gap-4 text-xs text-ink-secondary">
                <span className="flex items-center gap-1.5">
                  <span className="h-3 w-3 rounded-sm border border-teal-500 bg-teal-50" /> Available
                </span>
                <span className="flex items-center gap-1.5">
                  <span className="h-3 w-3 rounded-sm border border-amber-400 bg-amber-50" /> On Hold
                </span>
                <span className="flex items-center gap-1.5">
                  <span className="h-3 w-3 rounded-sm border border-slate-300 bg-slate-200" /> Booked
                </span>
                <span className="flex items-center gap-1.5">
                  <span className="h-3 w-3 rounded-sm border border-navy-700 bg-navy-700" /> Selected
                </span>
              </div>

              {/* Seat Grid */}
              {seats.length === 0 ? (
                <div className="py-8 text-center text-sm text-ink-muted">
                  No seats configured for this journey run.
                </div>
              ) : (
                <div className="max-h-60 overflow-y-auto rounded-xl border border-surface-border p-4 bg-slate-50/50">
                  <div className="grid grid-cols-4 sm:grid-cols-6 gap-2.5">
                    {seats.map((seat) => {
                      const isAvailable = seat.status === 'AVAILABLE';
                      const isSelected = selectedSeat?.seatId === seat.seatId;

                      return (
                        <button
                          key={seat.seatId}
                          type="button"
                          disabled={!isAvailable}
                          onClick={() => setSelectedSeat(seat)}
                          className={[
                            'flex flex-col items-center justify-center rounded-lg p-2.5 border text-xs font-semibold transition-interact',
                            isSelected
                              ? 'border-navy-700 bg-navy-700 text-white shadow-md ring-2 ring-navy-700/30'
                              : isAvailable
                                ? 'border-teal-400 bg-white text-teal-800 hover:bg-teal-50 hover:border-teal-500'
                                : seat.status === 'HOLD'
                                  ? 'border-amber-300 bg-amber-50 text-amber-700 cursor-not-allowed opacity-75'
                                  : 'border-slate-200 bg-slate-100 text-slate-400 cursor-not-allowed opacity-60',
                          ].join(' ')}
                        >
                          <span className="text-2xs opacity-80">{seat.coachNumber}</span>
                          <span className="font-mono text-sm font-bold">#{seat.seatNumber}</span>
                          <span className="text-[10px] uppercase font-normal">{seat.seatType}</span>
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

              {/* Action Bar */}
              <div className="mt-6 flex flex-col sm:flex-row items-center justify-between gap-3 border-t border-surface-border pt-4">
                <div className="text-xs text-ink-muted">
                  {selectedSeat ? (
                    <span className="text-ink-primary font-medium">
                      Selected: Coach <strong className="text-teal-700">{selectedSeat.coachNumber}</strong>, Seat{' '}
                      <strong className="text-teal-700">#{selectedSeat.seatNumber}</strong> ({selectedSeat.seatType})
                    </span>
                  ) : (
                    <span>Click on an available seat above to select.</span>
                  )}
                </div>

                <div className="flex items-center gap-2 w-full sm:w-auto">
                  <Button variant="secondary" size="sm" onClick={onClose} disabled={actionLoading}>
                    Cancel
                  </Button>
                  {authState.status === 'authenticated' ? (
                    <Button
                      variant="accent"
                      size="sm"
                      disabled={!selectedSeat || actionLoading}
                      isLoading={actionLoading}
                      onClick={handleHoldAndBook}
                    >
                      Hold & Proceed
                      <ArrowRight size={14} />
                    </Button>
                  ) : (
                    <Button
                      variant="primary"
                      size="sm"
                      onClick={() => navigate(ROUTES.LOGIN)}
                    >
                      <User size={14} />
                      Sign in to Book
                    </Button>
                  )}
                </div>
              </div>
            </div>
          ) : step === 'payment' && booking ? (
            <div>
              {/* Review Booking */}
              <div className="rounded-xl border border-surface-border bg-surface-subtle/30 p-4 space-y-3 mb-5">
                <div className="flex items-center justify-between border-b border-surface-border/60 pb-2">
                  <span className="text-xs text-ink-muted">Booking Reference</span>
                  <span className="font-mono text-sm font-bold text-navy-700">{booking.bookingRef}</span>
                </div>
                <div className="grid grid-cols-2 gap-3 text-xs">
                  <div>
                    <span className="text-ink-muted">Train:</span>
                    <p className="font-semibold text-ink-primary">{train.trainNumber} - {train.trainName}</p>
                  </div>
                  <div>
                    <span className="text-ink-muted">Seat:</span>
                    <p className="font-semibold text-ink-primary">Coach {booking.coachNumber}, #{booking.seatNumber}</p>
                  </div>
                  <div>
                    <span className="text-ink-muted">Journey:</span>
                    <p className="font-semibold text-ink-primary">{train.fromStationName} → {train.toStationName}</p>
                  </div>
                  <div>
                    <span className="text-ink-muted">Date:</span>
                    <p className="font-semibold text-ink-primary">{travelDate}</p>
                  </div>
                </div>
                <div className="flex items-center justify-between border-t border-surface-border/60 pt-2 text-sm font-bold text-navy-700">
                  <span>Total Amount Due:</span>
                  <span className="font-mono text-base text-teal-700">₹{booking.amount?.toFixed(2) ?? '500.00'}</span>
                </div>
              </div>

              {/* Payment Gateway Box */}
              <div className="rounded-xl border border-teal-200 bg-teal-50/50 p-4 mb-4">
                <div className="flex items-center gap-2 text-sm font-semibold text-teal-900 mb-1">
                  <CreditCard size={16} className="text-teal-700" />
                  Instant Payment (Mock Gateway)
                </div>
                <p className="text-xs text-teal-700 leading-relaxed">
                  Idempotent mock payment simulation verifying transaction isolation and double-spend protection.
                </p>
              </div>

              {/* Simulated Failure Toggle (for manual testing & resilience verification) */}
              <div className="mb-5 flex items-center gap-2 text-xs text-ink-secondary bg-surface-muted/50 p-2.5 rounded-lg border border-surface-border">
                <input
                  id="simulate-failure-checkbox"
                  type="checkbox"
                  checked={simulateFailure}
                  onChange={(e) => setSimulateFailure(e.target.checked)}
                  disabled={paymentAttemptPending}
                  className="rounded border-surface-border text-teal-600 focus:ring-teal-500 h-3.5 w-3.5"
                />
                <label htmlFor="simulate-failure-checkbox" className="cursor-pointer select-none">
                  Simulate gateway payment failure (test resilience and retry capability)
                </label>
              </div>

              {/* Actions */}
              <div className="flex items-center justify-end gap-2 border-t border-surface-border pt-4">
                <Button variant="secondary" size="sm" onClick={() => setStep('select-seat')} disabled={actionLoading}>
                  Back
                </Button>
                <Button
                  variant="accent"
                  size="md"
                  onClick={handlePay}
                  isLoading={actionLoading}
                  disabled={paymentAttemptPending && actionLoading}
                >
                  {error ? (
                    <>
                      <RefreshCw size={15} />
                      Retry Payment
                    </>
                  ) : (
                    <>
                      Pay ₹{booking.amount?.toFixed(2) ?? '500.00'} & Confirm
                      <CheckCircle2 size={16} />
                    </>
                  )}
                </Button>
              </div>
            </div>
          ) : step === 'confirmed' && payment ? (
            <div className="text-center py-4">
              <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-teal-50 text-teal-600 mb-3 shadow-inner">
                <CheckCircle2 size={32} />
              </div>
              <h3 className="text-xl font-bold text-ink-primary">Reservation Confirmed!</h3>
              <p className="text-xs text-ink-muted mt-1">
                Your ticket has been booked and your berth is confirmed.
              </p>

              <div className="my-5 mx-auto max-w-sm rounded-xl border border-surface-border bg-surface-subtle/40 p-4 text-left text-xs space-y-2">
                <div className="flex justify-between">
                  <span className="text-ink-muted">PNR / Ref:</span>
                  <span className="font-mono font-bold text-navy-700">{payment.bookingRef}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-ink-muted">Transaction ID:</span>
                  <span className="font-mono text-ink-primary">{payment.transactionRef}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-ink-muted">Status:</span>
                  <Badge variant="teal" size="sm">{payment.bookingStatus}</Badge>
                </div>
                <div className="flex justify-between">
                  <span className="text-ink-muted">Amount Paid:</span>
                  <span className="font-mono font-bold text-teal-700">₹{payment.amount?.toFixed(2)}</span>
                </div>
              </div>

              <div className="flex items-center justify-center gap-3 pt-2">
                <Button
                  variant="primary"
                  size="sm"
                  onClick={() => {
                    onClose();
                    navigate(ROUTES.BOOKINGS);
                  }}
                >
                  View in My Bookings
                </Button>
                <Button variant="secondary" size="sm" onClick={onClose}>
                  Done
                </Button>
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}
