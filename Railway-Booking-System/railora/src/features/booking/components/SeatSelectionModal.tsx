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
  Users,
  RefreshCw,
  MapPin,
  Receipt,
} from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Badge';
import { useAuth } from '@/features/auth/AuthContext';
import { bookingApi } from '../api';
import { clearPaymentAttempt, getOrCreatePaymentAttempt } from '../paymentAttempt';
import {
  isMockPaymentEnabled,
  isRazorpayCheckoutEnabled,
  isSimulatedPaymentEnabled,
  openRazorpayCheckout,
} from '../razorpayCheckout';
import { SimulatedCheckoutModal } from './SimulatedCheckoutModal';
import type {
  BookingResponseDTO,
  CoachAvailabilityDTO,
  FareQuoteResponseDTO,
  JourneyDTO,
  JourneyStopDTO,
  PaymentResponseDTO,
  SeatAvailabilityDTO,
  TravelClassAvailabilityDTO,
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

type ModalStep = 'select-seat' | 'passengers' | 'payment' | 'confirmed';

interface PassengerForm {
  fullName: string;
  age: string;
  gender: string;
  berthPreference: string;
}

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

  const [classes, setClasses] = useState<TravelClassAvailabilityDTO[]>([]);
  const [selectedClassCode, setSelectedClassCode] = useState<string | null>(null);
  const [selectedCoachNumber, setSelectedCoachNumber] = useState<string | null>(null);

  const [seats, setSeats] = useState<SeatAvailabilityDTO[]>([]);
  const [selectedSeats, setSelectedSeats] = useState<SeatAvailabilityDTO[]>([]);
  const [passengers, setPassengers] = useState<Record<number, PassengerForm>>({});
  const [heldSeatIds, setHeldSeatIds] = useState<number[]>([]);
  const [holdExpiresAt, setHoldExpiresAt] = useState<number | null>(null);
  const [remainingSeconds, setRemainingSeconds] = useState<number | null>(null);

  const [fareQuote, setFareQuote] = useState<FareQuoteResponseDTO | null>(null);

  const [booking, setBooking] = useState<BookingResponseDTO | null>(null);
  const [payment, setPayment] = useState<PaymentResponseDTO | null>(null);
  const [simulateFailure, setSimulateFailure] = useState(false);
  const [showSimulatedModal, setShowSimulatedModal] = useState(false);
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
        setError('Your seat hold has expired. Please select seats again.');
        setHeldSeatIds([]);
        setHoldExpiresAt(null);
        setBooking(null);
        setStep('select-seat');
      }
    }, 1000);

    return () => clearInterval(interval);
  }, [holdExpiresAt]);

  // Load directional journey, stops, classes, and seat availability when modal opens
  useEffect(() => {
    if (!isOpen) {
      setStep('select-seat');
      setSelectedSeats([]);
      setPassengers({});
      setSelectedClassCode(null);
      setSelectedCoachNumber(null);
      setClasses([]);
      setHeldSeatIds([]);
      setHoldExpiresAt(null);
      setBooking(null);
      setPayment(null);
      setError(null);
      setSimulateFailure(false);
      setPaymentAttemptPending(false);
      setFareQuote(null);
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

        // Prioritize requested journeyId candidate if present
        const candidateList = journeyId
          ? [
              ...journeys.filter((j) => j.journeyId === journeyId),
              ...journeys.filter((j) => j.journeyId !== journeyId),
            ]
          : journeys;

        // Find the specific journey run where origin precedes destination
        let matchedJourney: JourneyDTO | null = null;
        let matchedBoarding: JourneyStopDTO | null = null;
        let matchedAlighting: JourneyStopDTO | null = null;

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

        // Fetch class-grouped availability
        let classList: TravelClassAvailabilityDTO[] = [];
        if (typeof bookingApi.getJourneyClasses === 'function') {
          try {
            const res = await bookingApi.getJourneyClasses(
              matchedJourney.journeyId,
              matchedBoarding.journeyStopId,
              matchedAlighting.journeyStopId,
            );
            if (Array.isArray(res) && res.length > 0) {
              classList = res;
            }
          } catch {
            classList = [];
          }
        }

        const seatList = await bookingApi.getJourneySeats(
          matchedJourney.journeyId,
          matchedBoarding.journeyStopId,
          matchedAlighting.journeyStopId,
        );

        if (!active) return;

        // If classList is empty but seatList has seats, synthesize classes for UI grouping
        if (classList.length === 0 && seatList.length > 0) {
          const coachMap = new Map<string, SeatAvailabilityDTO[]>();
          for (const s of seatList) {
            const cList = coachMap.get(s.coachNumber) || [];
            cList.push(s);
            coachMap.set(s.coachNumber, cList);
          }
          const classMap = new Map<string, CoachAvailabilityDTO[]>();
          for (const [cNum, sList] of coachMap.entries()) {
            const cType =
              sList[0]?.coachType ||
              (cNum.startsWith('S')
                ? 'SL'
                : cNum.startsWith('B')
                  ? '3A'
                  : cNum.startsWith('A')
                    ? '2A'
                    : 'GEN');
            const total = sList.length;
            const avail = sList.filter((s) => s.status === 'AVAILABLE').length;
            const held = sList.filter((s) => s.status === 'HOLD').length;
            const booked = sList.filter((s) => s.status === 'BOOKED').length;
            const coachDto: CoachAvailabilityDTO = {
              coachId: sList[0]?.coachId ?? 0,
              coachNumber: cNum,
              coachType: cType,
              totalSeats: total,
              availableSeats: avail,
              heldSeats: held,
              bookedSeats: booked,
              seats: sList,
            };
            const list = classMap.get(cType) || [];
            list.push(coachDto);
            classMap.set(cType, list);
          }
          classList = Array.from(classMap.entries()).map(([cCode, cList]) => {
            const total = cList.reduce((acc, c) => acc + c.totalSeats, 0);
            const avail = cList.reduce((acc, c) => acc + c.availableSeats, 0);
            const held = cList.reduce((acc, c) => acc + c.heldSeats, 0);
            const booked = cList.reduce((acc, c) => acc + c.bookedSeats, 0);
            const name =
              cCode === 'SL'
                ? 'Sleeper'
                : cCode === '3A'
                  ? 'AC 3 Tier'
                  : cCode === '2A'
                    ? 'AC 2 Tier'
                    : cCode;
            const fare =
              cCode === 'SL' ? 500 : cCode === '3A' ? 1000 : cCode === '2A' ? 1500 : 500;
            return {
              classCode: cCode,
              className: name,
              totalSeats: total,
              availableSeats: avail,
              heldSeats: held,
              bookedSeats: booked,
              baseFare: fare,
              coaches: cList,
            };
          });
        }

        setClasses(classList);
        setSeats(seatList);

        if (classList.length > 0) {
          const initialClass = classList.find((c) => c.availableSeats > 0) || classList[0];
          setSelectedClassCode(initialClass.classCode);
          if (initialClass.coaches.length > 0) {
            setSelectedCoachNumber(initialClass.coaches[0].coachNumber);
          }
        }
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

  // Fetch dynamic server-authoritative fare quote whenever journey, class, or passenger count changes
  useEffect(() => {
    if (!journey || !selectedClassCode) {
      setFareQuote(null);
      return;
    }

    const currentJourneyId = journey.journeyId;
    const currentClass = selectedClassCode;
    const count = Math.max(1, selectedSeats.length);
    const bId = boardingStop?.journeyStopId;
    const aId = alightingStop?.journeyStopId;

    let active = true;
    async function loadFareQuote() {
      try {
        if (typeof bookingApi.getFareQuote === 'function') {
          const quote = await bookingApi.getFareQuote({
            journeyId: currentJourneyId,
            travelClass: currentClass,
            passengerCount: count,
            boardingJourneyStopId: bId,
            alightingJourneyStopId: aId,
          });
          if (active) {
            setFareQuote(quote);
          }
        }
      } catch {
        // Fall back gracefully
      }
    }

    void loadFareQuote();

    return () => {
      active = false;
    };
  }, [journey, selectedClassCode, selectedSeats.length, boardingStop?.journeyStopId, alightingStop?.journeyStopId]);

  if (!isOpen) return null;

  const currentClass = classes.find((c) => c.classCode === selectedClassCode) || null;
  const singleFare = fareQuote?.perPassengerFare.total ?? currentClass?.baseFare ?? null;
  const totalFare = fareQuote?.bookingTotal ?? (singleFare ? singleFare * Math.max(1, selectedSeats.length) : null);
  const distanceKm = fareQuote?.distanceKm ?? booking?.distanceKm ?? null;

  // Filter seats based on active class and coach selection
  const displayedSeats = seats.filter((s) => {
    if (selectedCoachNumber && s.coachNumber !== selectedCoachNumber) {
      return false;
    }
    if (selectedClassCode && s.coachType && s.coachType !== selectedClassCode) {
      return false;
    }
    return true;
  });

  function toggleSeatSelection(seat: SeatAvailabilityDTO) {
    if (seat.status !== 'AVAILABLE') return;
    setError(null);

    setSelectedSeats((prev) => {
      const alreadySelected = prev.some((s) => s.seatId === seat.seatId);
      if (alreadySelected) {
        return prev.filter((s) => s.seatId !== seat.seatId);
      }
      if (prev.length >= 6) {
        setError('Maximum of 6 seats allowed per booking.');
        return prev;
      }
      return [...prev, seat];
    });
  }

  function getPassengerData(seat: SeatAvailabilityDTO, index: number): PassengerForm {
    if (passengers[seat.seatId]) {
      return passengers[seat.seatId];
    }
    // Default the first passenger with authenticated user info if available
    const authenticatedUser = authState.status === 'authenticated' ? authState.user : null;
    const defaultName =
      index === 0 && authenticatedUser
        ? authenticatedUser.fullName || authenticatedUser.username
        : '';
    return {
      fullName: defaultName,
      age: '',
      gender: 'MALE',
      berthPreference: 'NO_PREFERENCE',
    };
  }

  function handlePassengerChange(
    seatId: number,
    field: keyof PassengerForm,
    value: string,
  ) {
    setPassengers((prev) => ({
      ...prev,
      [seatId]: {
        ...(prev[seatId] || {
          fullName: '',
          age: '',
          gender: 'MALE',
          berthPreference: 'NO_PREFERENCE',
        }),
        [field]: value,
      },
    }));
  }

  // Pre-validate passenger form details
  function validatePassengerDetails(): boolean {
    if (selectedSeats.length === 0) {
      setError('Please select at least one seat.');
      return false;
    }

    for (let i = 0; i < selectedSeats.length; i++) {
      const seat = selectedSeats[i];
      const p = getPassengerData(seat, i);

      if (!p.fullName || p.fullName.trim().length < 2) {
        setError(`Please enter a valid name (at least 2 characters) for Passenger ${i + 1} (Seat #${seat.seatNumber}).`);
        return false;
      }
      if (p.fullName.trim().length > 100) {
        setError(`Name for Passenger ${i + 1} must not exceed 100 characters.`);
        return false;
      }

      const ageNum = parseInt(p.age, 10);
      if (isNaN(ageNum) || ageNum < 1 || ageNum > 125) {
        setError(`Please enter a valid age between 1 and 125 for Passenger ${i + 1} (Seat #${seat.seatNumber}).`);
        return false;
      }

      if (!p.gender || !['MALE', 'FEMALE', 'OTHER'].includes(p.gender.toUpperCase())) {
        setError(`Please select a valid gender for Passenger ${i + 1}.`);
        return false;
      }
    }

    return true;
  }

  // Hold seats and create booking
  async function executeHoldAndBooking(useSynthesizedSinglePassenger: boolean = false) {
    if (authState.status !== 'authenticated') {
      navigate(ROUTES.LOGIN);
      return;
    }

    if (!journey || selectedSeats.length === 0 || !boardingStop || !alightingStop) return;

    if (!useSynthesizedSinglePassenger && !validatePassengerDetails()) {
      return;
    }

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

      const seatIdsToHold = selectedSeats.map((s) => s.seatId);
      const isBatch = selectedSeats.length > 1;

      // Check if already held
      const areAllHeld =
        holdExpiresAt &&
        holdExpiresAt > Date.now() &&
        seatIdsToHold.every((id) => heldSeatIds.includes(id));

      if (!areAllHeld) {
        if (isBatch && typeof bookingApi.holdSeats === 'function') {
          await bookingApi.holdSeats(
            journey.journeyId,
            {
              seatIds: seatIdsToHold,
              holdMinutes: 10,
              boardingJourneyStopId: boardingStop.journeyStopId,
              alightingJourneyStopId: alightingStop.journeyStopId,
              bookingMode: isTatkal ? 'TATKAL' : 'GENERAL',
              travelClass: selectedClassCode || selectedSeats[0].coachType,
              coachNumber: selectedCoachNumber || selectedSeats[0].coachNumber,
            },
            admissionToken,
          );
        } else {
          // Single-seat hold
          await bookingApi.holdSeat(
            journey.journeyId,
            {
              seatId: selectedSeats[0].seatId,
              holdMinutes: 10,
              boardingJourneyStopId: boardingStop.journeyStopId,
              alightingJourneyStopId: alightingStop.journeyStopId,
              bookingMode: isTatkal ? 'TATKAL' : 'GENERAL',
              travelClass: selectedClassCode || selectedSeats[0].coachType,
              coachNumber: selectedCoachNumber || selectedSeats[0].coachNumber,
            },
            admissionToken,
          );
        }
        setHeldSeatIds(seatIdsToHold);
        setHoldExpiresAt(Date.now() + 10 * 60 * 1000);
      }

      // Build passenger list
      const currentUser = authState.status === 'authenticated' ? authState.user : null;
      const passengerList = selectedSeats.map((s, idx) => {
        if (useSynthesizedSinglePassenger) {
          const defaultName = currentUser?.fullName || currentUser?.username || 'Passenger';
          return {
            fullName: defaultName,
            age: 30,
            gender: 'MALE',
            seatId: s.seatId,
            berthPreference: undefined,
          };
        }
        const p = getPassengerData(s, idx);
        return {
          fullName: p.fullName.trim(),
          age: parseInt(p.age, 10),
          gender: p.gender.toUpperCase(),
          seatId: s.seatId,
          berthPreference: p.berthPreference && p.berthPreference !== 'NO_PREFERENCE' ? p.berthPreference : undefined,
        };
      });

      // Create booking
      const newBooking = await bookingApi.createBooking(
        {
          journeyId: journey.journeyId,
          seatId: selectedSeats[0].seatId,
          boardingJourneyStopId: boardingStop.journeyStopId,
          alightingJourneyStopId: alightingStop.journeyStopId,
          bookingMode: isTatkal ? 'TATKAL' : 'GENERAL',
          travelClass: selectedClassCode || selectedSeats[0].coachType,
          coachNumber: selectedCoachNumber || selectedSeats[0].coachNumber,
          passengers: passengerList,
        },
        admissionToken,
      );

      setBooking(newBooking);
      setStep('payment');
    } catch (err: unknown) {
      const msg =
        err && typeof err === 'object' && 'response' in err
          ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
          : null;
      setError(msg || 'Could not reserve seat(s). One or more seats may have just been taken by another traveler.');
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
      if (isRazorpayCheckoutEnabled()) {
        if (!booking.amount || booking.amount <= 0) {
          throw new Error('A server-calculated fare is not available for this booking yet.');
        }
        const order = await bookingApi.createRazorpayOrder(booking.bookingRef);
        const checkoutResponse = await openRazorpayCheckout(order);
        const verification = await bookingApi.verifyRazorpayPayment(booking.bookingRef, checkoutResponse);
        const refreshedBooking = await bookingApi.getBookingByRef(booking.bookingRef);
        setBooking((current) => current
          ? {
              ...current,
              bookingStatus: refreshedBooking.bookingStatus,
              paymentStatus: refreshedBooking.paymentStatus,
            }
          : current);

        if (verification.paymentStatus !== 'SUCCESS' || refreshedBooking.bookingStatus !== 'CONFIRMED') {
          setError(verification.message || 'Payment is pending confirmation. Refresh your booking status shortly.');
          return;
        }

        if (!isMockPaymentEnabled()) {
          throw new Error('No payment provider is configured for this environment.');
        }

        setPayment({
          transactionRef: verification.transactionRef,
          bookingRef: verification.bookingRef,
          bookingId: booking.bookingId,
          amount: booking.amount,
          paymentStatus: refreshedBooking.paymentStatus,
          bookingStatus: refreshedBooking.bookingStatus,
          paymentMethod: 'RAZORPAY',
          message: verification.message,
          transactionTime: new Date().toISOString(),
        });
        setStep('confirmed');
        return;
      }

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
          setError(
            'This booking can no longer be paid because its seat hold has expired. Please select seats again.',
          );
          setBooking(null);
          setHeldSeatIds([]);
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
      setError(
        msg ||
          (err instanceof Error ? err.message : null) ||
          'Payment processing error. Please try again.',
      );
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
                {step === 'select-seat' && 'Select Your Seats'}
                {step === 'passengers' && 'Passenger Details'}
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
              <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-surface-border bg-surface-base p-3.5 text-xs text-ink-secondary">
                <span className="flex items-center gap-1.5">
                  <Calendar size={14} className="text-teal-600" />
                  Date: <strong className="text-ink-primary">{travelDate}</strong>
                </span>
                <span className="flex items-center gap-1.5">
                  <Clock size={14} className="text-teal-600" />
                  Departure: <strong className="text-ink-primary">{train.fromDepartureTime ?? 'Scheduled'}</strong>
                </span>
                {distanceKm && (
                  <span className="flex items-center gap-1.5">
                    <MapPin size={14} className="text-teal-600" />
                    Distance: <strong className="text-ink-primary font-mono">{distanceKm} km</strong>
                  </span>
                )}
                {singleFare !== null && (
                  <span className="flex items-center gap-1.5">
                    <ShieldCheck size={14} className="text-teal-600" />
                    Fare: <strong className="text-ink-primary font-mono text-sm">₹{singleFare.toFixed(2)}</strong> / berth
                  </span>
                )}
              </div>

              {/* Travel Class Selection */}
              {classes.length > 0 && (
                <div className="mb-4">
                  <span className="block text-xs font-semibold text-ink-secondary mb-2">
                    Select Travel Class:
                  </span>
                  <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5">
                    {classes.map((cls) => {
                      const isSelected = selectedClassCode === cls.classCode;
                      const hasAvailable = cls.availableSeats > 0;

                      return (
                        <button
                          key={cls.classCode}
                          type="button"
                          onClick={() => {
                            setSelectedClassCode(cls.classCode);
                            const firstCoach = cls.coaches[0]?.coachNumber || null;
                            setSelectedCoachNumber(firstCoach);
                            // Clear selected seats that are not in this new class
                            setSelectedSeats((prev) =>
                              prev.filter((s) => s.coachType === cls.classCode),
                            );
                          }}
                          className={[
                            'flex flex-col items-start p-3 rounded-xl border text-left transition-interact cursor-pointer',
                            isSelected
                              ? 'border-navy-700 bg-navy-50/70 ring-2 ring-navy-700/20 shadow-sm'
                              : hasAvailable
                                ? 'border-surface-border bg-white hover:border-slate-300 hover:bg-slate-50/50'
                                : 'border-surface-border bg-surface-muted/40 opacity-60 cursor-not-allowed',
                          ].join(' ')}
                        >
                          <div className="flex w-full items-center justify-between">
                            <span className="font-bold text-xs text-navy-800">{cls.className}</span>
                            <span className="font-mono text-2xs font-semibold px-1.5 py-0.5 rounded bg-surface-subtle text-ink-secondary">
                              {cls.classCode}
                            </span>
                          </div>
                          <div className="mt-1.5 flex w-full items-center justify-between text-2xs">
                            <span
                              className={
                                hasAvailable
                                  ? 'text-teal-700 font-semibold'
                                  : 'text-ink-muted font-normal'
                              }
                            >
                              {hasAvailable ? `${cls.availableSeats} available` : 'Sold out'}
                            </span>
                            <span className="font-mono font-bold text-ink-primary">
                              ₹{cls.baseFare.toFixed(0)}
                            </span>
                          </div>
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

              {/* Dynamic Server Fare Breakdown Card */}
              {fareQuote && (
                <div className="mb-4 rounded-xl border border-teal-200/80 bg-teal-50/40 p-3 text-2xs text-teal-900">
                  <div className="flex items-center justify-between font-semibold mb-1">
                    <span className="flex items-center gap-1 text-teal-800">
                      <Receipt size={13} />
                      Server Fare Breakdown ({fareQuote.travelClass} • {fareQuote.distanceKm} km):
                    </span>
                    <span className="font-mono text-xs text-teal-900 font-bold">
                      ₹{(fareQuote.perPassengerFare.total).toFixed(2)} / passenger
                    </span>
                  </div>
                  <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 pt-1 border-t border-teal-200/50 text-ink-secondary">
                    <div>Base: <span className="font-mono font-semibold text-ink-primary">₹{fareQuote.perPassengerFare.baseFare.toFixed(2)}</span></div>
                    <div>Reservation: <span className="font-mono font-semibold text-ink-primary">₹{fareQuote.perPassengerFare.reservationCharge.toFixed(2)}</span></div>
                    <div>Superfast: <span className="font-mono font-semibold text-ink-primary">₹{fareQuote.perPassengerFare.superfastCharge.toFixed(2)}</span></div>
                    <div>GST: <span className="font-mono font-semibold text-ink-primary">₹{(fareQuote.perPassengerFare.tax ?? fareQuote.perPassengerFare.gstAmount ?? 0).toFixed(2)}</span></div>
                  </div>
                </div>
              )}

              {/* Coach Selector Tabs (if multiple coaches exist in selected class) */}
              {currentClass && currentClass.coaches.length > 1 && (
                <div className="mb-4 flex items-center gap-2">
                  <span className="text-xs text-ink-muted">Coach:</span>
                  <div className="flex items-center gap-1.5">
                    {currentClass.coaches.map((coach) => {
                      const isCoachSelected = selectedCoachNumber === coach.coachNumber;
                      return (
                        <button
                          key={coach.coachNumber}
                          type="button"
                          onClick={() => {
                            setSelectedCoachNumber(coach.coachNumber);
                            // Filter selected seats that don't belong to this coach
                            setSelectedSeats((prev) =>
                              prev.filter((s) => s.coachNumber === coach.coachNumber),
                            );
                          }}
                          className={[
                            'px-3 py-1 rounded-lg text-xs font-semibold border transition-interact cursor-pointer',
                            isCoachSelected
                              ? 'border-navy-700 bg-navy-700 text-white shadow-sm'
                              : 'border-surface-border bg-white text-ink-secondary hover:bg-slate-50',
                          ].join(' ')}
                        >
                          {coach.coachNumber} ({coach.availableSeats} avail)
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

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
              {displayedSeats.length === 0 ? (
                <div className="py-8 text-center text-sm text-ink-muted">
                  No seats configured for this coach and class.
                </div>
              ) : (
                <div className="max-h-60 overflow-y-auto rounded-xl border border-surface-border p-4 bg-slate-50/50">
                  <div className="grid grid-cols-4 sm:grid-cols-6 gap-2.5">
                    {displayedSeats.map((seat) => {
                      const isAvailable = seat.status === 'AVAILABLE';
                      const isSelected = selectedSeats.some((s) => s.seatId === seat.seatId);

                      return (
                        <button
                          key={seat.seatId}
                          type="button"
                          disabled={!isAvailable}
                          onClick={() => toggleSeatSelection(seat)}
                          className={[
                            'flex flex-col items-center justify-center rounded-lg p-2.5 border text-xs font-semibold transition-interact cursor-pointer',
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
                  {selectedSeats.length > 0 ? (
                    <span className="text-ink-primary font-medium">
                      Selected ({selectedSeats.length}/6):{' '}
                      <strong className="text-teal-700">
                        {selectedSeats.map((s) => `#${s.seatNumber}`).join(', ')}
                      </strong>{' '}
                      (Coach {selectedSeats[0]?.coachNumber})
                      {totalFare !== null && (
                        <>
                          {' '}• Total:{' '}
                          <strong className="text-teal-700 font-mono">₹{totalFare.toFixed(2)}</strong>
                        </>
                      )}
                    </span>
                  ) : (
                    <span>Click on available seats above to select (up to 6 seats).</span>
                  )}
                </div>

                <div className="flex items-center gap-2 w-full sm:w-auto">
                  <Button variant="secondary" size="sm" onClick={onClose} disabled={actionLoading}>
                    Cancel
                  </Button>
                  {authState.status === 'authenticated' ? (
                    selectedSeats.length > 1 ? (
                      <Button
                        variant="accent"
                        size="sm"
                        disabled={selectedSeats.length === 0 || actionLoading}
                        onClick={() => setStep('passengers')}
                      >
                        <Users size={14} />
                        Add Passenger Details ({selectedSeats.length})
                        <ArrowRight size={14} />
                      </Button>
                    ) : (
                      <div className="flex items-center gap-2">
                        <Button
                          variant="secondary"
                          size="sm"
                          disabled={selectedSeats.length === 0 || actionLoading}
                          onClick={() => setStep('passengers')}
                        >
                          Passenger Details
                        </Button>
                        <Button
                          variant="accent"
                          size="sm"
                          disabled={selectedSeats.length === 0 || actionLoading}
                          isLoading={actionLoading}
                          onClick={() => executeHoldAndBooking(true)}
                        >
                          Hold & Proceed
                          <ArrowRight size={14} />
                        </Button>
                      </div>
                    )
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
          ) : step === 'passengers' ? (
            <div>
              {/* Passenger Details Step Header */}
              <div className="mb-4 flex items-center justify-between rounded-xl border border-surface-border bg-surface-base p-3 text-xs text-ink-secondary">
                <span className="flex items-center gap-1.5 font-semibold text-ink-primary">
                  <Users size={15} className="text-teal-600" />
                  {selectedSeats.length} {selectedSeats.length === 1 ? 'Passenger' : 'Passengers'}
                  {totalFare !== null ? ` • Total Fare: ₹${totalFare.toFixed(2)}` : ''}
                </span>
                <span className="text-ink-muted">
                  Coach {selectedSeats[0]?.coachNumber} ({selectedClassCode || selectedSeats[0]?.coachType})
                </span>
              </div>

              {/* Passenger Cards */}
              <div className="max-h-96 overflow-y-auto space-y-3 pr-1">
                {selectedSeats.map((seat, index) => {
                  const form = getPassengerData(seat, index);

                  return (
                    <div
                      key={seat.seatId}
                      className="rounded-xl border border-surface-border bg-surface-subtle/30 p-4"
                    >
                      <div className="flex items-center justify-between border-b border-surface-border/50 pb-2 mb-3">
                        <span className="text-xs font-bold text-navy-800 flex items-center gap-1.5">
                          <User size={14} className="text-teal-600" />
                          Passenger {index + 1}
                        </span>
                        <span className="font-mono text-2xs font-semibold px-2 py-0.5 rounded bg-navy-50 text-navy-700 border border-navy-200">
                          Coach {seat.coachNumber} • Seat #{seat.seatNumber} ({seat.seatType})
                        </span>
                      </div>

                      <div className="grid grid-cols-1 sm:grid-cols-12 gap-3 text-xs">
                        <div className="sm:col-span-5">
                          <label className="block text-ink-muted mb-1 font-medium">
                            Full Name <span className="text-red-500">*</span>
                          </label>
                          <input
                            type="text"
                            value={form.fullName}
                            onChange={(e) =>
                              handlePassengerChange(seat.seatId, 'fullName', e.target.value)
                            }
                            placeholder="e.g. Aarav Sharma"
                            className="w-full rounded-lg border border-surface-border bg-white px-3 py-1.5 text-xs text-ink-primary focus:border-teal-500 focus:outline-none"
                          />
                        </div>

                        <div className="sm:col-span-2">
                          <label className="block text-ink-muted mb-1 font-medium">
                            Age <span className="text-red-500">*</span>
                          </label>
                          <input
                            type="number"
                            min="1"
                            max="125"
                            value={form.age}
                            onChange={(e) =>
                              handlePassengerChange(seat.seatId, 'age', e.target.value)
                            }
                            placeholder="Age"
                            className="w-full rounded-lg border border-surface-border bg-white px-3 py-1.5 text-xs text-ink-primary focus:border-teal-500 focus:outline-none"
                          />
                        </div>

                        <div className="sm:col-span-2">
                          <label className="block text-ink-muted mb-1 font-medium">
                            Gender <span className="text-red-500">*</span>
                          </label>
                          <select
                            value={form.gender}
                            onChange={(e) =>
                              handlePassengerChange(seat.seatId, 'gender', e.target.value)
                            }
                            className="w-full rounded-lg border border-surface-border bg-white px-2 py-1.5 text-xs text-ink-primary focus:border-teal-500 focus:outline-none"
                          >
                            <option value="MALE">Male</option>
                            <option value="FEMALE">Female</option>
                            <option value="OTHER">Other</option>
                          </select>
                        </div>

                        <div className="sm:col-span-3">
                          <label className="block text-ink-muted mb-1 font-medium">
                            Berth Preference
                          </label>
                          <select
                            value={form.berthPreference}
                            onChange={(e) =>
                              handlePassengerChange(seat.seatId, 'berthPreference', e.target.value)
                            }
                            className="w-full rounded-lg border border-surface-border bg-white px-2 py-1.5 text-xs text-ink-primary focus:border-teal-500 focus:outline-none"
                          >
                            <option value="NO_PREFERENCE">No preference</option>
                            <option value="LOWER">Lower</option>
                            <option value="MIDDLE">Middle</option>
                            <option value="UPPER">Upper</option>
                            <option value="SIDE LOWER">Side Lower</option>
                            <option value="SIDE UPPER">Side Upper</option>
                          </select>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>

              {/* Actions */}
              <div className="mt-5 flex items-center justify-between border-t border-surface-border pt-4">
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => setStep('select-seat')}
                  disabled={actionLoading}
                >
                  Back to Seats
                </Button>
                <Button
                  variant="accent"
                  size="sm"
                  isLoading={actionLoading}
                  onClick={() => executeHoldAndBooking(false)}
                >
                  Hold & Proceed to Payment
                  <ArrowRight size={14} />
                </Button>
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
                    <span className="text-ink-muted">Seats:</span>
                    <p className="font-semibold text-ink-primary">
                      {booking.passengers && booking.passengers.length > 1
                        ? `${booking.passengers.length} berths (${booking.passengers.map((p) => `#${p.seatNumber}`).join(', ')})`
                        : `Coach ${booking.coachNumber}, #${booking.seatNumber}`}
                    </p>
                  </div>
                  <div>
                    <span className="text-ink-muted">Class:</span>
                    <p className="font-semibold text-ink-primary">
                      {currentClass?.className ?? selectedClassCode ?? 'Standard'} ({currentClass?.classCode ?? booking.coachNumber})
                    </p>
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

                {/* Passenger Roster Table in Payment Review */}
                {booking.passengers && booking.passengers.length > 0 && (
                  <div className="border-t border-surface-border/60 pt-2.5">
                    <span className="text-2xs font-semibold text-ink-secondary block mb-1.5">
                      Passenger Details ({booking.passengers.length}):
                    </span>
                    <div className="space-y-1.5">
                      {booking.passengers.map((p, idx) => (
                        <div
                          key={p.passengerId || idx}
                          className="flex items-center justify-between text-2xs bg-white rounded-lg border border-surface-border px-2.5 py-1.5"
                        >
                          <span className="font-semibold text-ink-primary">
                            {idx + 1}. {p.fullName} ({p.gender}, {p.age}y)
                          </span>
                          <span className="font-mono text-teal-700 font-bold">
                            Coach {p.coachNumber || booking.coachNumber}, #{p.seatNumber}
                            {p.berthPreference ? ` (${p.berthPreference})` : ''}
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {/* Itemized Dynamic Fare Breakdown */}
                {(booking.fareBreakdown || fareQuote) && (
                  <div className="border-t border-surface-border/60 pt-2.5">
                    <span className="text-2xs font-semibold text-ink-secondary block mb-1.5 flex items-center gap-1">
                      <Receipt size={13} className="text-teal-600" />
                      Itemized Server Fare Breakdown:
                    </span>
                    <div className="rounded-lg bg-white border border-surface-border p-2.5 text-2xs space-y-1">
                      {(booking.distanceKm ?? fareQuote?.distanceKm) && (
                        <div className="flex justify-between text-ink-muted">
                          <span>Distance:</span>
                          <span className="font-mono text-ink-primary font-semibold">{booking.distanceKm ?? fareQuote?.distanceKm} km</span>
                        </div>
                      )}
                      <div className="flex justify-between text-ink-muted">
                        <span>Base Fare ({selectedSeats.length || 1} berth{(selectedSeats.length || 1) > 1 ? 's' : ''}):</span>
                        <span className="font-mono text-ink-primary">
                          ₹{(booking.fareBreakdown?.baseFare ?? (fareQuote ? fareQuote.perPassengerFare.baseFare * Math.max(1, selectedSeats.length) : 0)).toFixed(2)}
                        </span>
                      </div>
                      <div className="flex justify-between text-ink-muted">
                        <span>Reservation Charge:</span>
                        <span className="font-mono text-ink-primary">
                          ₹{(booking.fareBreakdown?.reservationCharge ?? (fareQuote ? fareQuote.perPassengerFare.reservationCharge * Math.max(1, selectedSeats.length) : 0)).toFixed(2)}
                        </span>
                      </div>
                      <div className="flex justify-between text-ink-muted">
                        <span>Superfast Surcharge:</span>
                        <span className="font-mono text-ink-primary">
                          ₹{(booking.fareBreakdown?.superfastCharge ?? (fareQuote ? fareQuote.perPassengerFare.superfastCharge * Math.max(1, selectedSeats.length) : 0)).toFixed(2)}
                        </span>
                      </div>
                      <div className="flex justify-between text-ink-muted">
                        <span>Goods & Services Tax (GST):</span>
                        <span className="font-mono text-ink-primary">
                          ₹{(booking.fareBreakdown?.tax ?? booking.fareBreakdown?.gstAmount ?? (fareQuote ? (fareQuote.perPassengerFare.tax ?? fareQuote.perPassengerFare.gstAmount ?? 0) * Math.max(1, selectedSeats.length) : 0)).toFixed(2)}
                        </span>
                      </div>
                    </div>
                  </div>
                )}

                <div className="flex items-center justify-between border-t border-surface-border/60 pt-2 text-sm font-bold text-navy-700">
                  <span>Total Amount Due:</span>
                  <span className="font-mono text-base text-teal-700">
                    {booking.amount != null ? `₹${booking.amount.toFixed(2)}` : (totalFare != null ? `₹${totalFare.toFixed(2)}` : 'Unavailable')}
                  </span>
                </div>
              </div>

              {/* Payment Gateway Box */}
              <div className="rounded-xl border border-teal-200 bg-teal-50/50 p-4 mb-4">
                <div className="flex items-center gap-2 text-sm font-semibold text-teal-900 mb-1">
                  <CreditCard size={16} className="text-teal-700" />
                  {isRazorpayCheckoutEnabled()
                    ? 'Secure Payment (Razorpay Test Mode)'
                    : isSimulatedPaymentEnabled()
                      ? 'Demo Checkout (Simulated Gateway)'
                      : isMockPaymentEnabled()
                        ? 'Instant Payment (Mock Gateway)'
                        : 'Payments unavailable'}
                </div>
                <p className="text-xs text-teal-700 leading-relaxed">
                  {isRazorpayCheckoutEnabled()
                    ? 'Payment completion is verified by the Railora server before confirming your booking.'
                    : isSimulatedPaymentEnabled()
                      ? 'Interactive payment simulation for demonstration. No real money or bank accounts will be charged.'
                      : isMockPaymentEnabled()
                        ? 'Idempotent mock payment simulation verifying transaction isolation and double-spend protection.'
                        : 'Payment processing is not configured. Please contact support.'}
                </p>
              </div>

              {/* Simulated Failure Toggle */}
              {isMockPaymentEnabled() && (
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
              )}

              {/* Actions */}
              <div className="flex items-center justify-end gap-2 border-t border-surface-border pt-4">
                <Button variant="secondary" size="sm" onClick={() => setStep('select-seat')} disabled={actionLoading}>
                  Back
                </Button>
                {isSimulatedPaymentEnabled() && (
                  <Button
                    variant="accent"
                    size="md"
                    onClick={() => setShowSimulatedModal(true)}
                  >
                    <CreditCard size={15} />
                    Demo Checkout
                  </Button>
                )}
                {!isSimulatedPaymentEnabled() && (
                  <Button
                    variant="accent"
                    size="md"
                    onClick={handlePay}
                    isLoading={actionLoading}
                    disabled={
                      (paymentAttemptPending && actionLoading)
                      || (!isRazorpayCheckoutEnabled() && !isMockPaymentEnabled())
                      || (isRazorpayCheckoutEnabled() && (!booking.amount || booking.amount <= 0))
                    }
                  >
                    {error ? (
                      <>
                        <RefreshCw size={15} />
                        Retry Payment
                      </>
                    ) : (
                      <>
                        {isRazorpayCheckoutEnabled()
                          ? `Pay ₹${booking.amount?.toFixed(2) ?? '—'} with Razorpay`
                          : isMockPaymentEnabled()
                            ? `Pay ${booking.amount != null ? `₹${booking.amount.toFixed(2)}` : (totalFare != null ? `₹${totalFare.toFixed(2)}` : 'Unavailable')} & Confirm`
                            : 'Payments unavailable'}
                        <CheckCircle2 size={16} />
                      </>
                    )}
                  </Button>
                )}
              </div>
            </div>
          ) : step === 'confirmed' && payment ? (
            <div className="text-center py-4">
              <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-teal-50 text-teal-600 mb-3 shadow-inner">
                <CheckCircle2 size={32} />
              </div>
              <h3 className="text-xl font-bold text-ink-primary">Reservation Confirmed!</h3>
              <p className="text-xs text-ink-muted mt-1">
                Your ticket has been booked and your {selectedSeats.length > 1 ? 'berths are' : 'berth is'} confirmed.
              </p>

              <div className="my-5 mx-auto max-w-md rounded-xl border border-surface-border bg-surface-subtle/40 p-4 text-left text-xs space-y-2">
                {booking?.pnr ? (
                  <div className="flex justify-between">
                    <span className="text-ink-muted">PNR:</span>
                    <span className="font-mono font-bold text-navy-700">{booking.pnr}</span>
                  </div>
                ) : (
                  <div className="flex justify-between">
                    <span className="text-ink-muted">PNR / Ref:</span>
                    <span className="font-mono font-bold text-navy-700">{payment.bookingRef}</span>
                  </div>
                )}
                <div className="flex justify-between">
                  <span className="text-ink-muted">Booking Ref:</span>
                  <span className="font-mono font-bold text-navy-700">{payment.bookingRef}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-ink-muted">Transaction ID:</span>
                  <span className="font-mono text-ink-primary">{payment.transactionRef}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-ink-muted">Payment Mode:</span>
                  <span className={payment.paymentMethod?.toUpperCase() === 'SIMULATED' || payment.transactionRef?.startsWith('SIM-') ? 'font-semibold text-teal-700' : 'font-mono text-ink-primary'}>
                    {payment.paymentMethod?.toUpperCase() === 'SIMULATED' || payment.transactionRef?.startsWith('SIM-')
                      ? 'SIMULATED (Demo)'
                      : (payment.paymentMethod || 'Standard')}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-ink-muted">Status:</span>
                  <Badge variant="teal" size="sm">{payment.bookingStatus}</Badge>
                </div>
                <div className="flex justify-between">
                  <span className="text-ink-muted">Amount Paid:</span>
                  <span className="font-mono font-bold text-teal-700">₹{payment.amount?.toFixed(2)}</span>
                </div>
                {booking?.distanceKm && (
                  <div className="flex justify-between">
                    <span className="text-ink-muted">Distance:</span>
                    <span className="font-mono font-bold text-ink-primary">{booking.distanceKm} km</span>
                  </div>
                )}
                {booking?.fareBreakdown && (
                  <div className="flex justify-between">
                    <span className="text-ink-muted">Breakdown:</span>
                    <span className="font-mono text-ink-secondary text-2xs">
                      Base ₹{booking.fareBreakdown.baseFare.toFixed(2)} + Res ₹{booking.fareBreakdown.reservationCharge.toFixed(2)} + SF ₹{booking.fareBreakdown.superfastCharge.toFixed(2)} + GST ₹{(booking.fareBreakdown.tax ?? booking.fareBreakdown.gstAmount ?? 0).toFixed(2)}
                    </span>
                  </div>
                )}

                {/* Confirmed Passenger List */}
                {booking?.passengers && booking.passengers.length > 0 && (
                  <div className="border-t border-surface-border/60 pt-2 mt-2">
                    <span className="text-2xs font-semibold text-ink-secondary block mb-1">
                      Confirmed Passengers:
                    </span>
                    <div className="space-y-1">
                      {booking.passengers.map((p, idx) => (
                        <div
                          key={p.passengerId || idx}
                          className="flex justify-between text-2xs bg-white rounded px-2 py-1 border border-surface-border/50"
                        >
                          <span className="font-medium text-ink-primary">
                            {idx + 1}. {p.fullName} ({p.gender}, {p.age}y)
                          </span>
                          <span className="font-mono font-bold text-teal-700">
                            Coach {p.coachNumber || booking.coachNumber}, #{p.seatNumber}
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
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

      {showSimulatedModal && booking && (
        <SimulatedCheckoutModal
          isOpen={showSimulatedModal}
          onClose={() => setShowSimulatedModal(false)}
          bookingRef={booking.bookingRef}
          initialSummary={{
            bookingRef: booking.bookingRef,
            bookingId: booking.bookingId,
            pnr: booking.pnr,
            trainNumber: train.trainNumber,
            trainName: train.trainName,
            fromStationCode: train.fromStationCode,
            fromStationName: train.fromStationName,
            toStationCode: train.toStationCode,
            toStationName: train.toStationName,
            journeyDate: travelDate,
            departureTime: train.fromDepartureTime ?? null,
            arrivalTime: train.toArrivalTime ?? null,
            coachNumber: booking.coachNumber,
            seatNumber: booking.seatNumber,
            amount: booking.amount,
            bookingStatus: booking.bookingStatus,
            paymentStatus: booking.paymentStatus,
          }}
          onSuccess={(res) => {
            setShowSimulatedModal(false);
            setPayment(res);
            setStep('confirmed');
          }}
        />
      )}
    </div>
  );
}
