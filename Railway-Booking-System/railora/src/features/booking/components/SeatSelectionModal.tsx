import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
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
  MapPin,
  Receipt,
  Printer,
  Lock,
  Smartphone,
  Building2,
  Copy,
} from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Badge';
import { useAuth } from '@/features/auth/AuthContext';
import { bookingApi } from '../api';
import { clearPaymentAttempt } from '../paymentAttempt';
import {
  isMockPaymentEnabled,
  isRazorpayCheckoutEnabled,
  openRazorpayCheckout,
} from '../razorpayCheckout';
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
import { parseHoldExpiry } from '@/lib/utils';

export const STANDARD_CLASS_NAMES: Record<string, string> = {
  '1A': 'AC First Class',
  '2A': 'AC 2 Tier',
  '3A': 'AC 3 Tier',
  '3E': 'AC 3 Economy',
  'SL': 'Sleeper',
  'CC': 'AC Chair Car',
  'EC': 'Executive Chair Car',
  '2S': 'Second Sitting',
};

export const PENDING_BOOKING_STORAGE_KEY = 'railora_pending_booking_intent';

export interface PendingBookingIntent {
  journeyId: number;
  trainNumber: string;
  trainName: string;
  travelDate: string;
  fromStationCode: string;
  toStationCode: string;
  selectedClassCode: string;
  selectedCoachNumber: string;
  selectedSeatNumbers: number[];
  passengers?: Record<number, PassengerForm>;
  step: ModalStep;
  timestamp: number;
}

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
  const [restoredNotification, setRestoredNotification] = useState<string | null>(null);

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
  const [paymentMode, setPaymentMode] = useState<'UPI' | 'CARD' | 'NETBANKING'>('UPI');
  const [upiId, setUpiId] = useState('traveler@okhdfcbank');
  const [selectedBank, setSelectedBank] = useState('SBI');
  const [cardNumber, setCardNumber] = useState('4532 •••• •••• 8891');
  const [cardExpiry, setCardExpiry] = useState('08/29');
  const [cardCvv, setCardCvv] = useState('742');
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
              STANDARD_CLASS_NAMES[cCode] ||
              (cCode === 'SL'
                ? 'Sleeper'
                : cCode === '3A'
                  ? 'AC 3 Tier'
                  : cCode === '2A'
                    ? 'AC 2 Tier'
                    : cCode);
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

        // Enrich class names and fetch authoritative dynamic fare quotes from backend API
        for (const cls of classList) {
          if (STANDARD_CLASS_NAMES[cls.classCode]) {
            cls.className = STANDARD_CLASS_NAMES[cls.classCode];
          }
          try {
            if (typeof bookingApi.getFareQuote === 'function') {
              const quote = await bookingApi.getFareQuote({
                journeyId: matchedJourney.journeyId,
                travelClass: cls.classCode,
                passengerCount: 1,
                boardingJourneyStopId: matchedBoarding.journeyStopId,
                alightingJourneyStopId: matchedAlighting.journeyStopId,
              });
              if (quote?.perPassengerFare?.total) {
                cls.baseFare = quote.perPassengerFare.total;
              }
            }
          } catch {
            // Keep backend baseFare
          }
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

        // Restore in-flight booking intent if preserved in sessionStorage
        try {
          const savedIntentRaw = sessionStorage.getItem(PENDING_BOOKING_STORAGE_KEY);
          if (savedIntentRaw) {
            const intent: PendingBookingIntent = JSON.parse(savedIntentRaw);
            const isFresh = Date.now() - (intent.timestamp || 0) < 30 * 60 * 1000;
            const isMatch =
              intent.journeyId === matchedJourney.journeyId ||
              (intent.trainNumber.trim() === train.trainNumber.trim() &&
                intent.travelDate === travelDate);

            if (isFresh && isMatch) {
              if (
                intent.selectedClassCode &&
                classList.some((c) => c.classCode === intent.selectedClassCode)
              ) {
                setSelectedClassCode(intent.selectedClassCode);
              }
              if (intent.selectedCoachNumber) {
                setSelectedCoachNumber(intent.selectedCoachNumber);
              }

              // Re-validate seats against fresh authoritative backend seats
              const matchedSeats = seatList.filter(
                (s) =>
                  intent.selectedSeatNumbers.includes(s.seatNumber) &&
                  (!intent.selectedCoachNumber || s.coachNumber === intent.selectedCoachNumber),
              );

              const availableSeats = matchedSeats.filter((s) => s.status === 'AVAILABLE');
              const unavailableSeats = matchedSeats.filter((s) => s.status !== 'AVAILABLE');

              if (availableSeats.length > 0) {
                setSelectedSeats(availableSeats);
                if (intent.passengers) {
                  setPassengers(intent.passengers);
                }

                if (unavailableSeats.length > 0) {
                  setError(
                    `Some previously selected seats (${unavailableSeats.map((s) => `#${s.seatNumber}`).join(', ')}) were booked while signing in. Your remaining available seats have been retained.`,
                  );
                  setStep('select-seat');
                } else {
                  setRestoredNotification(
                    `Welcome back! Your seat selection (${availableSeats.map((s) => `#${s.seatNumber}`).join(', ')}) has been restored.`,
                  );
                  if (intent.step === 'passengers') {
                    setStep('passengers');
                  }
                }
              } else if (intent.selectedSeatNumbers.length > 0) {
                setError(
                  'Your previously selected seats are no longer available. Please select from currently available seats.',
                );
                setStep('select-seat');
              }
            }
            sessionStorage.removeItem(PENDING_BOOKING_STORAGE_KEY);
          }
        } catch {
          // Ignore parsing/sessionStorage errors
        }
      } catch (err: unknown) {
        if (!active) return;
        let errorMessage = 'Failed to load seat availability. Please try again.';
        if (axios.isAxiosError<{ message?: string }>(err)) {
          const serverMessage = err.response?.data?.message;
          if (serverMessage && typeof serverMessage === 'string') {
            const firstLine = serverMessage.split('\n')[0].trim();
            if (
              firstLine.length > 0 &&
              !firstLine.includes('Exception:') &&
              !firstLine.includes('at in.yograj') &&
              !firstLine.includes('org.hibernate')
            ) {
              errorMessage = firstLine;
            }
          } else if (err.message && !err.message.includes('status code 500')) {
            errorMessage = err.message;
          }
        } else if (err instanceof Error && err.message) {
          errorMessage = err.message;
        }
        setError(errorMessage);
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

  const handleSignInToBook = (
    targetStep: ModalStep = selectedSeats.length > 0 ? 'passengers' : 'select-seat',
  ) => {
    if (!journey) return;
    const intent: PendingBookingIntent = {
      journeyId: journey.journeyId,
      trainNumber: train.trainNumber,
      trainName: train.trainName,
      travelDate: travelDate || '',
      fromStationCode: train.fromStationCode,
      toStationCode: train.toStationCode,
      selectedClassCode: selectedClassCode || '',
      selectedCoachNumber: selectedCoachNumber || '',
      selectedSeatNumbers: selectedSeats.map((s) => s.seatNumber),
      passengers,
      step: targetStep,
      timestamp: Date.now(),
    };

    try {
      sessionStorage.setItem(PENDING_BOOKING_STORAGE_KEY, JSON.stringify(intent));
    } catch {
      // Ignore sessionStorage errors
    }

    const returnUrl = `/search?from=${encodeURIComponent(train.fromStationCode)}&to=${encodeURIComponent(train.toStationCode)}&date=${encodeURIComponent(travelDate || '')}&autoOpenTrain=${encodeURIComponent(train.trainNumber)}`;
    navigate(`${ROUTES.LOGIN}?redirect=${encodeURIComponent(returnUrl)}`);
  };

  // Hold seats and create booking
  async function executeHoldAndBooking(useSynthesizedSinglePassenger: boolean = false) {
    if (authState.status !== 'authenticated') {
      handleSignInToBook(useSynthesizedSinglePassenger ? 'select-seat' : 'passengers');
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
        let serverHoldExpiresAt: string | null = null;
        if (isBatch && typeof bookingApi.holdSeats === 'function') {
          const res = await bookingApi.holdSeats(
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
          if (Array.isArray(res) && res.length > 0 && res[0].holdExpiresAt) {
            serverHoldExpiresAt = res[0].holdExpiresAt;
          }
        } else {
          // Single-seat hold
          const res = await bookingApi.holdSeat(
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
          if (res && res.holdExpiresAt) {
            serverHoldExpiresAt = res.holdExpiresAt;
          }
        }
        setHeldSeatIds(seatIdsToHold);
        const now = Date.now();
        const parsedServerHold = parseHoldExpiry(serverHoldExpiresAt);
        const validHold = parsedServerHold && parsedServerHold - now > 60000
          ? parsedServerHold
          : (now + 10 * 60 * 1000);
        setHoldExpiresAt(validHold);
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
      if (newBooking.holdExpiresAt) {
        const parsedBookingHold = parseHoldExpiry(newBooking.holdExpiresAt);
        if (parsedBookingHold && parsedBookingHold - Date.now() > 60000) {
          setHoldExpiresAt(parsedBookingHold);
        }
      }
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

      const simMethod =
        paymentMode === 'CARD'
          ? 'SIMULATED_CARD'
          : paymentMode === 'NETBANKING'
          ? 'SIMULATED_NETBANKING'
          : 'SIMULATED_UPI';
      const txnKey = `TXN-${Date.now()}-${Math.floor(Math.random() * 100000)}`;

      let paymentResult: PaymentResponseDTO;
      if (typeof bookingApi.simulatePaymentSuccess === 'function') {
        try {
          paymentResult = await bookingApi.simulatePaymentSuccess(booking.bookingRef, {
            idempotencyKey: txnKey,
            reason: simMethod,
          });
        } catch {
          paymentResult = await bookingApi.payBooking(booking.bookingRef, {
            paymentMethod: paymentMode,
            simulateFailure: false,
          });
        }
      } else {
        paymentResult = await bookingApi.payBooking(booking.bookingRef, {
          paymentMethod: paymentMode,
          simulateFailure: false,
        });
      }

      if (paymentResult.paymentStatus === 'FAILED') {
        clearPaymentAttempt(booking.bookingRef);
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

      // Re-fetch booking from backend to guarantee latest 10-digit PNR and berth allocations
      try {
        const refreshed = await bookingApi.getBookingByRef(booking.bookingRef);
        if (refreshed) {
          setBooking(refreshed);
        }
      } catch {
        // Continue with current booking
      }

      clearPaymentAttempt(booking.bookingRef);
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

  function renderSeatButton(seat: SeatAvailabilityDTO) {
    const isAvailable = seat.status === 'AVAILABLE';
    const isSelected = selectedSeats.some((s) => s.seatId === seat.seatId);
    const isOnHold = seat.status === 'HOLD';
    const isBooked = seat.status === 'BOOKED';

    return (
      <button
        key={seat.seatId}
        type="button"
        disabled={!isAvailable}
        onClick={() => toggleSeatSelection(seat)}
        className={[
          'flex flex-col items-center justify-center rounded-lg p-2.5 border text-xs font-semibold transition-interact cursor-pointer relative',
          isSelected
            ? 'border-navy-700 bg-navy-700 text-white shadow-md ring-2 ring-navy-700/30'
            : isAvailable
              ? 'border-teal-500 bg-white text-teal-800 hover:bg-teal-50 hover:border-teal-600 shadow-2xs'
              : isOnHold
                ? 'border-amber-300 bg-amber-50 text-amber-700 cursor-not-allowed opacity-80'
                : isBooked
                  ? 'border-slate-200 bg-slate-100 text-slate-400 cursor-not-allowed opacity-60'
                  : 'border-slate-200 bg-slate-100 text-slate-400 cursor-not-allowed opacity-60',
        ].join(' ')}
        aria-label={`Seat ${seat.seatNumber}, Coach ${seat.coachNumber}, ${seat.seatType}, ${seat.status}`}
      >
        <div className="flex w-full items-center justify-between text-2xs mb-0.5">
          <span className={isSelected ? 'text-teal-200' : 'text-ink-muted'}>
            {seat.coachNumber}
          </span>
          {isSelected && <span className="font-bold text-teal-300">✓</span>}
        </div>
        <span className="font-mono text-sm font-extrabold tracking-tight">
          #{seat.seatNumber}
        </span>
        <span
          className={[
            'text-[9px] uppercase font-bold tracking-tight mt-0.5 px-1 rounded',
            isSelected
              ? 'bg-navy-800 text-teal-300'
              : isAvailable
                ? 'bg-teal-50 text-teal-700'
                : isOnHold
                  ? 'bg-amber-100 text-amber-800'
                  : 'bg-slate-200 text-slate-500',
          ].join(' ')}
        >
          {seat.seatType}
        </span>
      </button>
    );
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-2 sm:p-4 bg-navy-950/80 backdrop-blur-sm overflow-hidden"
      role="dialog"
      aria-modal="true"
      aria-labelledby="modal-headline"
    >
      <div className="relative w-full max-w-2xl h-[92vh] max-h-[850px] flex flex-col rounded-2xl bg-white shadow-2xl border border-surface-border overflow-hidden">
        {/* Header */}
        <div className="flex-shrink-0 flex items-center justify-between border-b border-surface-border bg-surface-subtle/40 px-6 py-4">
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

        {/* Visual Step Indicator */}
        {journey && step !== 'confirmed' && (
          <div className="flex-shrink-0 flex items-center justify-between border-b border-surface-border bg-surface-subtle/20 px-6 py-2.5 text-xs font-semibold">
            <div className={`flex items-center gap-1.5 ${step === 'select-seat' ? 'text-teal-700 font-bold' : 'text-ink-muted'}`}>
              <span className={`flex h-5 w-5 items-center justify-center rounded-full text-2xs ${step === 'select-seat' ? 'bg-teal-700 text-white font-bold' : 'bg-surface-muted text-ink-muted'}`}>1</span>
              <span>Class & Berths</span>
            </div>
            <div className="h-0.5 w-6 sm:w-12 bg-surface-border" />
            <div className={`flex items-center gap-1.5 ${step === 'passengers' ? 'text-teal-700 font-bold' : 'text-ink-muted'}`}>
              <span className={`flex h-5 w-5 items-center justify-center rounded-full text-2xs ${step === 'passengers' ? 'bg-teal-700 text-white font-bold' : 'bg-surface-muted text-ink-muted'}`}>2</span>
              <span>Passenger Details</span>
            </div>
            <div className="h-0.5 w-6 sm:w-12 bg-surface-border" />
            <div className={`flex items-center gap-1.5 ${step === 'payment' ? 'text-teal-700 font-bold' : 'text-ink-muted'}`}>
              <span className={`flex h-5 w-5 items-center justify-center rounded-full text-2xs ${step === 'payment' ? 'bg-teal-700 text-white font-bold' : 'bg-surface-muted text-ink-muted'}`}>3</span>
              <span>Review & Pay</span>
            </div>
          </div>
        )}

        {/* Modal Body */}
        <div className="p-4 sm:p-6 overflow-y-auto flex-1">
          {error && (
            <div className="mb-4 flex items-start gap-2.5 rounded-xl border border-status-danger-border bg-status-danger-bg p-3.5 text-sm text-status-danger-text">
              <AlertCircle size={18} className="shrink-0 mt-0.5" />
              <div>{error}</div>
            </div>
          )}

          {restoredNotification && (
            <div className="mb-4 flex items-center justify-between rounded-xl border border-teal-200 bg-teal-50 px-3.5 py-2.5 text-xs text-teal-800">
              <span className="flex items-center gap-1.5 font-medium">
                <CheckCircle2 size={16} className="text-teal-600 shrink-0" />
                {restoredNotification}
              </span>
              <button
                type="button"
                onClick={() => setRestoredNotification(null)}
                className="text-teal-600 hover:text-teal-800 font-bold ml-2 text-sm leading-none"
                aria-label="Dismiss restored notification"
              >
                ×
              </button>
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
          ) : !journey ? (
            <div className="py-8 text-center space-y-4">
              <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-amber-50 text-amber-600 border border-amber-200">
                <Train size={28} />
              </div>
              <div>
                <h3 className="text-base font-bold text-ink-primary">
                  Demo Booking Unavailable for this Train/Date
                </h3>
                <p className="text-xs text-ink-muted max-w-md mx-auto mt-1 leading-relaxed">
                  {error || `No scheduled journey run found for ${train.trainNumber} on ${travelDate}. Please choose another date.`}
                </p>
              </div>
              <div className="mx-auto max-w-md rounded-xl border border-teal-200 bg-teal-50/60 p-3.5 text-left text-xs">
                <div className="font-semibold text-teal-900 mb-1 flex items-center gap-1.5">
                  <ShieldCheck size={14} className="text-teal-700" />
                  Configured Production Demo Run
                </div>
                <p className="text-2xs text-teal-800 leading-normal">
                  A complete 16-seat operational inventory run is actively provisioned for:
                  <br />
                  <span className="font-bold text-navy-800">Train 12301</span> • <strong>HWH → NDLS</strong> • <strong>1 December 2026</strong> (Coaches S1, B1, A1).
                </p>
              </div>
              <div className="pt-2">
                <Button variant="secondary" size="sm" onClick={onClose}>
                  Close
                </Button>
              </div>
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
                      Railora Demo Fare Quote ({fareQuote.travelClass} • {fareQuote.distanceKm} km
                      {fareQuote.distanceSource === 'ESTIMATED_RUN_DURATION' ? ' · Estimated' : ''}):
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
                  <p className="mt-1.5 pt-1 border-t border-teal-200/30 text-3xs text-ink-muted">
                    Simulated fare calculated by Railora Demo Engine for demonstration purposes. Not an official Indian Railways fare.
                  </p>
                </div>
              )}

              {/* Coach Selector Tabs */}
              {currentClass && currentClass.coaches.length > 0 && (
                <div className="mb-4 flex flex-wrap items-center gap-2">
                  <span className="text-xs font-semibold text-ink-secondary">Coach:</span>
                  <div className="flex items-center gap-1.5 flex-wrap">
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
                          Coach {coach.coachNumber} ({coach.availableSeats} of {coach.totalSeats} avail)
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

              {/* Seat Layout (Partitioned into Main and Side Berths) */}
              {displayedSeats.length === 0 ? (
                <div className="py-8 text-center text-sm text-ink-muted">
                  No seats configured for this coach and class.
                </div>
              ) : (() => {
                const mainBerths = displayedSeats.filter(
                  (s) => !s.seatType.toUpperCase().startsWith('SIDE'),
                );
                const sideBerths = displayedSeats.filter((s) =>
                  s.seatType.toUpperCase().startsWith('SIDE'),
                );

                return (
                  <div className="max-h-64 overflow-y-auto rounded-xl border border-surface-border p-4 bg-slate-50/50">
                    {sideBerths.length > 0 ? (
                      <div className="grid grid-cols-1 sm:grid-cols-12 gap-3">
                        {/* Main Cabin Berths (LOWER, MIDDLE, UPPER) */}
                        <div className="sm:col-span-8 border-b sm:border-b-0 sm:border-r border-surface-border pb-3 sm:pb-0 sm:pr-3">
                          <div className="text-2xs font-bold text-ink-muted uppercase tracking-wider mb-2 flex items-center justify-between">
                            <span>Cabin Berths (Main)</span>
                            <span className="font-mono text-2xs text-teal-700">
                              {mainBerths.filter((s) => s.status === 'AVAILABLE').length} of {mainBerths.length} available
                            </span>
                          </div>
                          <div className="grid grid-cols-3 gap-2">
                            {mainBerths.map((seat) => renderSeatButton(seat))}
                          </div>
                        </div>

                        {/* Gangway / Aisle Indicator */}
                        <div className="hidden sm:flex sm:col-span-1 items-center justify-center">
                          <div className="h-full border-l border-dashed border-slate-300 flex items-center justify-center">
                            <span className="transform -rotate-90 text-[10px] uppercase font-bold tracking-widest text-slate-400 select-none">
                              Aisle
                            </span>
                          </div>
                        </div>

                        {/* Side Berths (SIDE LOWER, SIDE UPPER) */}
                        <div className="sm:col-span-3">
                          <div className="text-2xs font-bold text-ink-muted uppercase tracking-wider mb-2 flex items-center justify-between">
                            <span>Side Berths</span>
                            <span className="font-mono text-2xs text-teal-700">
                              {sideBerths.filter((s) => s.status === 'AVAILABLE').length} of {sideBerths.length} available
                            </span>
                          </div>
                          <div className="grid grid-cols-2 sm:grid-cols-1 gap-2">
                            {sideBerths.map((seat) => renderSeatButton(seat))}
                          </div>
                        </div>
                      </div>
                    ) : (
                      <div className="grid grid-cols-4 sm:grid-cols-6 gap-2.5">
                        {displayedSeats.map((seat) => renderSeatButton(seat))}
                      </div>
                    )}
                  </div>
                );
              })()}

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
                    <div className="flex items-center gap-2">
                      {selectedSeats.length > 0 && (
                        <Button
                          variant="secondary"
                          size="sm"
                          onClick={() => setStep('passengers')}
                        >
                          <Users size={14} />
                          Passenger Details ({selectedSeats.length})
                        </Button>
                      )}
                      <Button
                        variant="primary"
                        size="sm"
                        disabled={selectedSeats.length === 0}
                        onClick={() => handleSignInToBook('passengers')}
                      >
                        <User size={14} />
                        Sign in to Book
                      </Button>
                    </div>
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
                {authState.status === 'authenticated' ? (
                  <Button
                    variant="accent"
                    size="sm"
                    isLoading={actionLoading}
                    onClick={() => executeHoldAndBooking(false)}
                  >
                    Hold & Proceed to Payment
                    <ArrowRight size={14} />
                  </Button>
                ) : (
                  <Button
                    variant="primary"
                    size="sm"
                    onClick={() => handleSignInToBook('passengers')}
                  >
                    <User size={14} />
                    Sign in to Proceed to Payment
                  </Button>
                )}
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
                      Railora Demo Fare Breakdown:
                    </span>
                    <div className="rounded-lg bg-white border border-surface-border p-2.5 text-2xs space-y-1">
                      {(booking.distanceKm ?? fareQuote?.distanceKm) && (
                        <div className="flex justify-between text-ink-muted">
                          <span>Distance:</span>
                          <span className="font-mono text-ink-primary font-semibold">
                            {booking.distanceKm ?? fareQuote?.distanceKm} km
                            {fareQuote?.distanceSource === 'ESTIMATED_RUN_DURATION' ? ' (Estimated)' : ''}
                          </span>
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

              {/* Payment Mode Selector (IRCTC Style) */}
              <div className="rounded-xl border border-surface-border bg-white p-4 space-y-3">
                <div className="flex items-center justify-between border-b border-surface-border pb-2">
                  <span className="text-xs font-bold text-navy-800 flex items-center gap-1.5">
                    <CreditCard size={15} className="text-teal-600" />
                    Select Payment Mode
                  </span>
                  <span className="flex items-center gap-1 text-2xs text-teal-700 font-medium">
                    <ShieldCheck size={13} />
                    256-Bit SSL Encrypted
                  </span>
                </div>

                {/* Tabs */}
                <div className="grid grid-cols-3 gap-2">
                  <button
                    type="button"
                    onClick={() => setPaymentMode('UPI')}
                    className={`flex flex-col items-center justify-center p-2.5 rounded-xl border text-xs font-semibold transition-interact ${
                      paymentMode === 'UPI'
                        ? 'border-teal-600 bg-teal-50/60 text-teal-900 shadow-xs'
                        : 'border-surface-border hover:bg-surface-subtle text-ink-secondary'
                    }`}
                  >
                    <Smartphone size={18} className="mb-1 text-teal-600" />
                    <span>UPI / QR</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => setPaymentMode('CARD')}
                    className={`flex flex-col items-center justify-center p-2.5 rounded-xl border text-xs font-semibold transition-interact ${
                      paymentMode === 'CARD'
                        ? 'border-teal-600 bg-teal-50/60 text-teal-900 shadow-xs'
                        : 'border-surface-border hover:bg-surface-subtle text-ink-secondary'
                    }`}
                  >
                    <CreditCard size={18} className="mb-1 text-teal-600" />
                    <span>Credit / Debit</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => setPaymentMode('NETBANKING')}
                    className={`flex flex-col items-center justify-center p-2.5 rounded-xl border text-xs font-semibold transition-interact ${
                      paymentMode === 'NETBANKING'
                        ? 'border-teal-600 bg-teal-50/60 text-teal-900 shadow-xs'
                        : 'border-surface-border hover:bg-surface-subtle text-ink-secondary'
                    }`}
                  >
                    <Building2 size={18} className="mb-1 text-teal-600" />
                    <span>Net Banking</span>
                  </button>
                </div>

                {/* Tab Content */}
                {paymentMode === 'UPI' && (
                  <div className="rounded-lg bg-surface-subtle/50 p-3 space-y-2 text-xs">
                    <label className="block text-2xs font-semibold text-ink-secondary">
                      Enter UPI ID / VPA (Google Pay, PhonePe, Paytm, BHIM)
                    </label>
                    <div className="flex gap-2">
                      <input
                        type="text"
                        value={upiId}
                        onChange={(e) => setUpiId(e.target.value)}
                        placeholder="e.g. yourname@okhdfcbank"
                        className="flex-1 rounded-lg border border-surface-border bg-white px-3 py-2 text-xs text-ink-primary font-mono focus:border-teal-500 focus:outline-none"
                      />
                      <span className="flex items-center text-2xs text-teal-700 font-semibold px-2 bg-teal-50 rounded-lg border border-teal-200">
                        Verified
                      </span>
                    </div>
                    <p className="text-3xs text-ink-muted">
                      A payment request will be authorized instantly to confirm your reservation.
                    </p>
                  </div>
                )}

                {paymentMode === 'CARD' && (
                  <div className="rounded-lg bg-surface-subtle/50 p-3 space-y-2 text-xs">
                    <div>
                      <label className="block text-2xs font-semibold text-ink-secondary mb-1">
                        Card Number
                      </label>
                      <input
                        type="text"
                        value={cardNumber}
                        onChange={(e) => setCardNumber(e.target.value)}
                        className="w-full rounded-lg border border-surface-border bg-white px-3 py-2 text-xs font-mono text-ink-primary focus:border-teal-500 focus:outline-none"
                        placeholder="•••• •••• •••• ••••"
                      />
                    </div>
                    <div className="grid grid-cols-2 gap-2">
                      <div>
                        <label className="block text-2xs font-semibold text-ink-secondary mb-1">
                          Expiry (MM/YY)
                        </label>
                        <input
                          type="text"
                          value={cardExpiry}
                          onChange={(e) => setCardExpiry(e.target.value)}
                          className="w-full rounded-lg border border-surface-border bg-white px-3 py-2 text-xs font-mono text-ink-primary focus:border-teal-500 focus:outline-none"
                        />
                      </div>
                      <div>
                        <label className="block text-2xs font-semibold text-ink-secondary mb-1">
                          CVV
                        </label>
                        <input
                          type="password"
                          value={cardCvv}
                          maxLength={4}
                          onChange={(e) => setCardCvv(e.target.value)}
                          className="w-full rounded-lg border border-surface-border bg-white px-3 py-2 text-xs font-mono text-ink-primary focus:border-teal-500 focus:outline-none"
                        />
                      </div>
                    </div>
                  </div>
                )}

                {paymentMode === 'NETBANKING' && (
                  <div className="rounded-lg bg-surface-subtle/50 p-3 space-y-2 text-xs">
                    <label className="block text-2xs font-semibold text-ink-secondary mb-1">
                      Choose Your Bank
                    </label>
                    <div className="grid grid-cols-3 gap-2">
                      {['SBI', 'HDFC', 'ICICI', 'AXIS', 'PNB', 'KOTAK'].map((bank) => (
                        <button
                          key={bank}
                          type="button"
                          onClick={() => setSelectedBank(bank)}
                          className={`px-3 py-2 rounded-lg text-xs font-bold border transition-interact text-center ${
                            selectedBank === bank
                              ? 'bg-navy-800 text-white border-navy-800'
                              : 'bg-white text-ink-primary border-surface-border hover:border-teal-500'
                          }`}
                        >
                          {bank}
                        </button>
                      ))}
                    </div>
                  </div>
                )}
              </div>

              {/* Actions */}
              <div className="flex items-center justify-between border-t border-surface-border pt-4">
                <Button variant="secondary" size="sm" onClick={() => setStep('passengers')} disabled={actionLoading}>
                  Back to Passengers
                </Button>
                <Button
                  variant="accent"
                  size="md"
                  onClick={handlePay}
                  isLoading={actionLoading}
                  className="font-bold shadow-md"
                >
                  <Lock size={14} />
                  Pay ₹{booking.amount != null ? booking.amount.toFixed(2) : (totalFare != null ? totalFare.toFixed(2) : '')} & Confirm Ticket
                  <ArrowRight size={14} />
                </Button>
              </div>
            </div>
          ) : step === 'confirmed' && payment ? (
            <div className="text-center py-2 space-y-4">
              <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-teal-50 text-teal-600 shadow-sm border border-teal-200">
                <CheckCircle2 size={32} />
              </div>
              <div>
                <h3 className="text-xl font-bold text-ink-primary">Reservation Confirmed!</h3>
                <p className="text-xs text-ink-muted mt-0.5">
                  Your ticket has been booked successfully and berth allocations are confirmed.
                </p>
              </div>

              {/* PNR Highlight Card */}
              <div className="mx-auto max-w-lg rounded-2xl border-2 border-teal-500/30 bg-teal-50/60 p-4 text-center shadow-xs">
                <span className="text-2xs font-extrabold uppercase tracking-widest text-teal-800">
                  Indian Railways Electronic Ticket • PNR Number
                </span>
                <div className="flex items-center justify-center gap-2.5 my-1.5">
                  <span className="font-mono text-2xl sm:text-3xl font-black text-navy-800 tracking-wider">
                    {booking?.pnr || payment.bookingRef}
                  </span>
                  <button
                    type="button"
                    onClick={() => {
                      const pnrToCopy = booking?.pnr || payment.bookingRef;
                      void navigator.clipboard.writeText(pnrToCopy);
                      setRestoredNotification('PNR copied to clipboard!');
                      setTimeout(() => setRestoredNotification(null), 3000);
                    }}
                    title="Copy PNR"
                    className="rounded-lg p-1.5 text-teal-700 hover:bg-teal-100 transition-interact"
                  >
                    <Copy size={18} />
                  </button>
                </div>
                <div className="flex items-center justify-center gap-2">
                  <span className="inline-block px-3 py-0.5 rounded-full text-2xs font-extrabold bg-teal-600 text-white shadow-2xs">
                    STATUS: CONFIRMED (CNF)
                  </span>
                  <span className="text-2xs text-ink-muted font-mono">
                    Txn: {payment.transactionRef}
                  </span>
                </div>
              </div>

              {/* Journey Details Card */}
              <div className="mx-auto max-w-lg rounded-xl border border-surface-border bg-surface-subtle/30 p-4 text-left text-xs space-y-3">
                <div className="grid grid-cols-2 gap-3 border-b border-surface-border pb-3">
                  <div>
                    <span className="text-ink-muted block text-2xs">Train</span>
                    <p className="font-bold text-navy-800">{train.trainNumber} - {train.trainName}</p>
                  </div>
                  <div>
                    <span className="text-ink-muted block text-2xs">Class</span>
                    <p className="font-bold text-navy-800">
                      {currentClass?.className ?? selectedClassCode ?? 'Standard'} ({currentClass?.classCode ?? booking?.coachNumber})
                    </p>
                  </div>
                  <div>
                    <span className="text-ink-muted block text-2xs">From / Departure</span>
                    <p className="font-semibold text-ink-primary">
                      {train.fromStationName} ({train.fromStationCode}) • {train.fromDepartureTime ?? 'Scheduled'}
                    </p>
                  </div>
                  <div>
                    <span className="text-ink-muted block text-2xs">To / Arrival</span>
                    <p className="font-semibold text-ink-primary">
                      {train.toStationName} ({train.toStationCode}) • {train.toArrivalTime ?? 'Scheduled'}
                    </p>
                  </div>
                </div>

                {/* Confirmed Passenger & Berth Table */}
                {booking?.passengers && booking.passengers.length > 0 && (
                  <div>
                    <span className="text-2xs font-bold text-ink-secondary block mb-1.5">
                      Confirmed Passengers & Berths:
                    </span>
                    <div className="overflow-hidden rounded-lg border border-surface-border bg-white">
                      <table className="w-full text-left text-2xs">
                        <thead className="bg-surface-subtle text-ink-muted border-b border-surface-border">
                          <tr>
                            <th className="py-1.5 px-3">#</th>
                            <th className="py-1.5 px-3">Passenger</th>
                            <th className="py-1.5 px-3">Age/Sex</th>
                            <th className="py-1.5 px-3">Coach</th>
                            <th className="py-1.5 px-3">Berth</th>
                            <th className="py-1.5 px-3">Status</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-surface-border">
                          {booking.passengers.map((p, idx) => (
                            <tr key={p.passengerId || idx} className="hover:bg-teal-50/30">
                              <td className="py-2 px-3 font-semibold text-ink-muted">{idx + 1}</td>
                              <td className="py-2 px-3 font-bold text-ink-primary">{p.fullName}</td>
                              <td className="py-2 px-3 text-ink-secondary">{p.age} / {p.gender}</td>
                              <td className="py-2 px-3 font-mono font-bold text-navy-800">{p.coachNumber || booking.coachNumber}</td>
                              <td className="py-2 px-3 font-mono font-bold text-teal-700">#{p.seatNumber} ({p.berthPreference || 'LOWER'})</td>
                              <td className="py-2 px-3">
                                <span className="px-1.5 py-0.5 rounded text-3xs font-extrabold bg-teal-100 text-teal-800">
                                  CNF
                                </span>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </div>
                )}

                {/* Payment Summary */}
                <div className="flex items-center justify-between border-t border-surface-border pt-2 text-2xs">
                  <span className="text-ink-muted">Total Fare Paid:</span>
                  <span className="font-mono text-sm font-black text-teal-700">
                    ₹{payment.amount?.toFixed(2)}
                  </span>
                </div>
              </div>

              {/* Action Buttons */}
              <div className="flex flex-wrap items-center justify-center gap-3 pt-2">
                <Button
                  variant="secondary"
                  size="md"
                  onClick={() => window.print()}
                  aria-label="Print Ticket"
                  className="gap-1.5 font-semibold"
                >
                  <Printer size={16} />
                  Print E-Ticket
                </Button>
                <Button
                  variant="primary"
                  size="md"
                  onClick={() => {
                    onClose();
                    navigate(ROUTES.BOOKINGS);
                  }}
                  className="gap-1.5 font-semibold"
                >
                  <Receipt size={16} />
                  View in My Bookings
                </Button>
                <Button
                  variant="secondary"
                  size="md"
                  onClick={onClose}
                >
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
