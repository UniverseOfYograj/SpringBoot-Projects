import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  AlertCircle,
  AlertTriangle,
  ArrowRight,
  Building2,
  Calendar,
  Check,
  CheckCircle2,
  Clock,
  CreditCard,
  Lock,
  QrCode,
  RefreshCw,
  ShieldCheck,
  Smartphone,
  Ticket,
  Train,
  Wallet,
  X,
  XCircle,
} from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Badge';
import { bookingApi } from '../api';
import type { PaymentResponseDTO, SimulatedPaymentSummaryDTO } from '../types';
import { parseHoldExpiry } from '@/lib/utils';
import { ROUTES } from '@/lib/constants';

export interface SimulatedCheckoutModalProps {
  isOpen: boolean;
  onClose: () => void;
  bookingRef: string;
  initialSummary?: Partial<SimulatedPaymentSummaryDTO>;
  onSuccess?: (payment: PaymentResponseDTO) => void;
  onFailure?: (payment: PaymentResponseDTO) => void;
}

type PaymentTab = 'upi' | 'card' | 'netbanking' | 'wallet';

const POPULAR_BANKS = [
  { code: 'SBI', name: 'State Bank of India', short: 'SBI' },
  { code: 'HDFC', name: 'HDFC Bank', short: 'HDFC' },
  { code: 'ICICI', name: 'ICICI Bank', short: 'ICICI' },
  { code: 'AXIS', name: 'Axis Bank', short: 'Axis' },
  { code: 'PNB', name: 'Punjab National Bank', short: 'PNB' },
  { code: 'BOB', name: 'Bank of Baroda', short: 'BOB' },
];

export function SimulatedCheckoutModal({
  isOpen,
  onClose,
  bookingRef,
  initialSummary,
  onSuccess,
  onFailure,
}: SimulatedCheckoutModalProps) {
  const navigate = useNavigate();

  const [summary, setSummary] = useState<SimulatedPaymentSummaryDTO | null>(null);
  const [loadingSummary, setLoadingSummary] = useState(false);
  const [summaryError, setSummaryError] = useState<string | null>(null);

  const [activeTab, setActiveTab] = useState<PaymentTab>('upi');
  const [upiId, setUpiId] = useState('railora@demoupi');
  const [cardNumber, setCardNumber] = useState('4532 •••• •••• 8891');
  const [cardExpiry, setCardExpiry] = useState('08/29');
  const [cardCvv, setCardCvv] = useState('742');
  const [cardHolder, setCardHolder] = useState('Demo Traveler');
  const [selectedBank, setSelectedBank] = useState('SBI');

  const [actionLoading, setActionLoading] = useState<'success' | 'failure' | null>(null);
  const [processingStage, setProcessingStage] = useState<number>(0);
  const [processingMessage, setProcessingMessage] = useState<string>('');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const [resultState, setResultState] = useState<'idle' | 'processing' | 'success' | 'failed'>('idle');
  const [paymentResult, setPaymentResult] = useState<PaymentResponseDTO | null>(null);
  const [showConfirmDialog, setShowConfirmDialog] = useState(false);

  // Idempotency key per payment attempt (regenerated on retry)
  const [idempotencyKey, setIdempotencyKey] = useState<string>(() => `SIM-${crypto.randomUUID()}`);
  const [attemptNumber, setAttemptNumber] = useState<number>(1);

  // Authoritative Seat hold countdown timer (using parseHoldExpiry)
  const [secondsRemaining, setSecondsRemaining] = useState<number | null>(null);
  const inFlightRef = useRef(false);

  // Body scroll lock
  useEffect(() => {
    if (isOpen) {
      const prev = document.body.style.overflow;
      document.body.style.overflow = 'hidden';
      return () => {
        document.body.style.overflow = prev;
      };
    }
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen || !bookingRef) return;

    // Reset state on open
    setResultState('idle');
    setPaymentResult(null);
    setErrorMessage(null);
    setShowConfirmDialog(false);
    setIdempotencyKey(`SIM-${crypto.randomUUID()}`);
    setAttemptNumber(1);
    setProcessingStage(0);

    async function fetchSummary() {
      setLoadingSummary(true);
      setSummaryError(null);
      try {
        const data = await bookingApi.getSimulatedPaymentSummary(bookingRef);
        setSummary(data);
      } catch (err: unknown) {
        // Fallback to initialSummary if available
        if (initialSummary && initialSummary.bookingRef === bookingRef) {
          setSummary({
            bookingRef,
            bookingId: initialSummary.bookingId ?? 0,
            pnr: initialSummary.pnr ?? null,
            journeyId: initialSummary.journeyId ?? null,
            trainNumber: initialSummary.trainNumber ?? 'TRAIN',
            trainName: initialSummary.trainName ?? 'Express',
            fromStationCode: initialSummary.fromStationCode ?? 'FROM',
            fromStationName: initialSummary.fromStationName ?? 'Origin Station',
            toStationCode: initialSummary.toStationCode ?? 'TO',
            toStationName: initialSummary.toStationName ?? 'Destination Station',
            journeyDate: initialSummary.journeyDate ?? '',
            departureTime: initialSummary.departureTime ?? '',
            arrivalTime: initialSummary.arrivalTime ?? '',
            coachNumber: initialSummary.coachNumber ?? '',
            coachType: initialSummary.coachType ?? '',
            seatNumber: initialSummary.seatNumber ?? 0,
            seatType: initialSummary.seatType ?? '',
            amount: initialSummary.amount ?? 500,
            bookingStatus: initialSummary.bookingStatus ?? 'PENDING_PAYMENT',
            paymentStatus: initialSummary.paymentStatus ?? 'PENDING',
            holdExpiresAt: initialSummary.holdExpiresAt ?? null,
            mode: 'SIMULATED',
          });
        } else {
          const msg =
            err && typeof err === 'object' && 'response' in err
              ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
              : null;
          setSummaryError(msg || 'Unable to load payment details for this booking.');
        }
      } finally {
        setLoadingSummary(false);
      }
    }

    void fetchSummary();
  }, [isOpen, bookingRef]);

  // Handle hold expiry timer using parseHoldExpiry
  useEffect(() => {
    const rawExpiry = summary?.holdExpiresAt;
    if (!rawExpiry) {
      setSecondsRemaining(null);
      return;
    }

    const expiryTime = parseHoldExpiry(rawExpiry);
    if (!expiryTime) {
      setSecondsRemaining(null);
      return;
    }

    function tick() {
      const diff = Math.max(0, Math.floor((expiryTime! - Date.now()) / 1000));
      setSecondsRemaining(diff);
      if (diff === 0 && resultState === 'idle') {
        setErrorMessage('Your seat hold has expired. This booking can no longer be confirmed.');
      }
    }

    tick();
    const interval = setInterval(tick, 1000);
    return () => clearInterval(interval);
  }, [summary?.holdExpiresAt, resultState]);

  if (!isOpen) return null;

  // Detect whether we are in automated test environment (skip animated delays)
  const isTestEnv =
    typeof process !== 'undefined' &&
    (process.env?.NODE_ENV === 'test' || Boolean(process.env?.VITEST));

  async function handleSimulateSuccess() {
    if (inFlightRef.current || !bookingRef) return;
    inFlightRef.current = true;
    setActionLoading('success');
    setErrorMessage(null);

    try {
      if (!isTestEnv) {
        setResultState('processing');
        setProcessingStage(1);
        setProcessingMessage('Connecting to secure banking network...');
        await new Promise((r) => setTimeout(r, 600));

        setProcessingStage(2);
        setProcessingMessage(
          activeTab === 'upi'
            ? 'Authorizing UPI mandate with PSP app...'
            : activeTab === 'card'
            ? 'Simulating 3D-Secure OTP verification...'
            : activeTab === 'wallet'
            ? 'Deducting from Railora Demo Wallet...'
            : 'Authorizing net banking transaction...'
        );
        await new Promise((r) => setTimeout(r, 700));

        setProcessingStage(3);
        setProcessingMessage('Confirming berth with Indian Railways PRS...');
        await new Promise((r) => setTimeout(r, 400));
      }

      const res = await bookingApi.simulatePaymentSuccess(bookingRef, {
        idempotencyKey,
      });
      setPaymentResult(res);
      setResultState('success');
      if (onSuccess) {
        onSuccess(res);
      }
    } catch (err: unknown) {
      const msg =
        err && typeof err === 'object' && 'response' in err
          ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
          : null;
      setErrorMessage(msg || 'Simulated payment processing encountered an error.');
      setResultState('idle');
    } finally {
      inFlightRef.current = false;
      setActionLoading(null);
      setProcessingStage(0);
    }
  }

  async function handleSimulateFailure() {
    if (inFlightRef.current || !bookingRef) return;
    inFlightRef.current = true;
    setActionLoading('failure');
    setErrorMessage(null);

    try {
      if (!isTestEnv) {
        setResultState('processing');
        setProcessingStage(1);
        setProcessingMessage('Connecting to simulated banking network...');
        await new Promise((r) => setTimeout(r, 600));

        setProcessingStage(2);
        setProcessingMessage('Evaluating transaction rules (simulating decline)...');
        await new Promise((r) => setTimeout(r, 600));
      }

      const res = await bookingApi.simulatePaymentFailure(bookingRef, {
        idempotencyKey,
        reason: 'Simulated payment failure (user selected in demo gateway)',
      });
      setPaymentResult(res);
      setResultState('failed');
      if (onFailure) {
        onFailure(res);
      }
    } catch (err: unknown) {
      const msg =
        err && typeof err === 'object' && 'response' in err
          ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
          : null;
      setErrorMessage(msg || 'Payment simulation failed to execute.');
      setResultState('idle');
    } finally {
      inFlightRef.current = false;
      setActionLoading(null);
      setProcessingStage(0);
    }
  }

  function handleRetry() {
    setIdempotencyKey(`SIM-${crypto.randomUUID()}`);
    setAttemptNumber((prev) => prev + 1);
    setResultState('idle');
    setPaymentResult(null);
    setErrorMessage(null);
    setShowConfirmDialog(false);
    setProcessingStage(0);
  }

  function formatTimer(seconds: number): string {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  }

  const formattedAmount = summary?.amount != null ? `₹${summary.amount.toFixed(2)}` : '₹500.00';

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="simulated-checkout-title"
      className="fixed inset-0 z-[60] flex items-center justify-center p-3 sm:p-4 bg-navy-950/75 backdrop-blur-xs animate-in fade-in"
    >
      <div className="relative w-full max-w-xl max-h-[90vh] flex flex-col rounded-2xl border border-surface-border bg-surface-card shadow-modal overflow-hidden">
        {/* Sticky Header */}
        <div className="flex-shrink-0 flex items-center justify-between border-b border-surface-border/60 px-5 py-4 bg-surface-card">
          <div className="flex items-center gap-2.5">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-teal-50 text-teal-700 shadow-xs">
              <Ticket size={20} />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 id="simulated-checkout-title" className="text-base sm:text-lg font-bold text-ink-primary">
                  Demo Checkout
                </h3>
                <Badge variant="warning" size="sm">
                  DEMO MODE
                </Badge>
              </div>
              <p className="text-2xs sm:text-xs text-ink-muted">Simulated payment environment</p>
            </div>
          </div>

          <button
            type="button"
            onClick={onClose}
            disabled={actionLoading !== null || resultState === 'processing'}
            className="text-ink-muted hover:text-ink-primary p-1.5 rounded-lg hover:bg-surface-subtle transition-colors disabled:opacity-50"
            aria-label="Close demo checkout modal"
          >
            <X size={18} />
          </button>
        </div>

        {/* Scrollable Body */}
        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-4">
          {/* DEMO NOTICE BANNER (Strict Requirement & Disclosure) */}
          <div
            role="status"
            className="rounded-xl border border-amber-300 bg-amber-50 p-3.5 text-amber-900 shadow-xs"
          >
            <div className="flex items-start gap-2.5">
              <AlertTriangle size={18} className="text-amber-700 shrink-0 mt-0.5" />
              <div className="text-xs space-y-1">
                <p className="font-extrabold uppercase tracking-wide text-amber-900">
                  DEMO MODE — NO REAL MONEY WILL BE CHARGED.
                </p>
                <p className="text-amber-800 leading-relaxed">
                  This is strictly a simulation of the payment outcome. Do not enter real card numbers, UPI PINs, CVVs, or bank credentials.
                </p>
              </div>
            </div>
          </div>

          {/* Loading Spinner */}
          {loadingSummary ? (
            <div className="py-12 text-center">
              <div className="mx-auto h-8 w-8 animate-spin rounded-full border-2 border-teal-600 border-t-transparent" />
              <p className="mt-3 text-sm text-ink-muted">Loading journey details and fare…</p>
            </div>
          ) : summaryError ? (
            <div className="rounded-xl border border-status-danger-border bg-status-danger-bg p-4 text-sm text-status-danger-text">
              <div className="flex items-center gap-2">
                <AlertCircle size={18} className="shrink-0" />
                <span>{summaryError}</span>
              </div>
              <div className="mt-4 flex justify-end">
                <Button variant="secondary" size="sm" onClick={onClose}>
                  Close
                </Button>
              </div>
            </div>
          ) : resultState === 'processing' ? (
            /* ─── REALISTIC PROCESSING SCREEN ─── */
            <div className="py-8 px-4 text-center space-y-5 animate-in fade-in">
              <div className="relative mx-auto h-16 w-16">
                <div className="absolute inset-0 rounded-full border-4 border-teal-100 animate-ping opacity-75" />
                <div className="relative flex h-16 w-16 items-center justify-center rounded-full bg-teal-50 text-teal-600 border-2 border-teal-500 shadow-sm">
                  <Lock size={26} className="animate-pulse" />
                </div>
              </div>

              <div>
                <h4 className="text-lg font-bold text-ink-primary">Processing Payment Simulation</h4>
                <p className="text-xs text-ink-muted mt-1 max-w-sm mx-auto">
                  {processingMessage || 'Authorizing demo transaction with payment switch…'}
                </p>
              </div>

              {/* Progress Steps */}
              <div className="max-w-xs mx-auto space-y-2 text-2xs text-left bg-surface-subtle/40 p-3 rounded-xl border border-surface-border">
                <div className={`flex items-center gap-2 ${processingStage >= 1 ? 'text-teal-700 font-semibold' : 'text-ink-muted'}`}>
                  <span className={`h-2 w-2 rounded-full ${processingStage >= 1 ? 'bg-teal-600' : 'bg-slate-300'}`} />
                  <span>1. Secure Banking Gateway Handshake</span>
                </div>
                <div className={`flex items-center gap-2 ${processingStage >= 2 ? 'text-teal-700 font-semibold' : 'text-ink-muted'}`}>
                  <span className={`h-2 w-2 rounded-full ${processingStage >= 2 ? 'bg-teal-600' : 'bg-slate-300'}`} />
                  <span>2. Payment Authorization & 3D Secure</span>
                </div>
                <div className={`flex items-center gap-2 ${processingStage >= 3 ? 'text-teal-700 font-semibold' : 'text-ink-muted'}`}>
                  <span className={`h-2 w-2 rounded-full ${processingStage >= 3 ? 'bg-teal-600' : 'bg-slate-300'}`} />
                  <span>3. Indian Railways PRS Confirmation</span>
                </div>
              </div>
            </div>
          ) : resultState === 'success' && paymentResult ? (
            /* ─── SUCCESS RESULT SCREEN ─── */
            <div className="text-center py-4 space-y-4 animate-in fade-in">
              <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-teal-50 text-teal-600 shadow-inner">
                <CheckCircle2 size={32} />
              </div>
              <div>
                <h4 className="text-xl font-bold text-ink-primary">Reservation Confirmed!</h4>
                <p className="text-xs text-ink-muted mt-1">
                  Simulated payment processed successfully. Your train berth is confirmed.
                </p>
              </div>

              <div className="mx-auto max-w-md rounded-xl border border-surface-border bg-surface-subtle/40 p-4 text-left text-xs space-y-2.5">
                {(summary?.pnr || paymentResult.bookingRef) && (
                  <div className="flex items-center justify-between border-b border-surface-border/60 pb-2">
                    <span className="text-ink-muted font-medium">PNR Number:</span>
                    <span className="font-mono text-sm font-extrabold text-navy-800 bg-navy-50 px-2 py-0.5 rounded border border-navy-200">
                      {summary?.pnr || 'GENERATED'}
                    </span>
                  </div>
                )}
                <div className="flex items-center justify-between">
                  <span className="text-ink-muted">Booking Reference:</span>
                  <span className="font-mono text-ink-secondary">{paymentResult.bookingRef}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-ink-muted">Transaction Reference:</span>
                  <span className="font-mono text-ink-secondary">{paymentResult.transactionRef}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-ink-muted">Payment Mode:</span>
                  <span className="font-semibold text-teal-700">SIMULATED (Demo)</span>
                </div>
                <div className="flex items-center justify-between border-t border-surface-border/60 pt-2 font-bold">
                  <span className="text-ink-primary">Amount Paid:</span>
                  <span className="font-mono text-sm text-teal-700">
                    ₹{paymentResult.amount.toFixed(2)}
                  </span>
                </div>
              </div>

              <div className="flex flex-col sm:flex-row items-center justify-center gap-3 pt-2">
                <Button
                  variant="accent"
                  size="md"
                  onClick={() => {
                    onClose();
                    if (summary?.pnr) {
                      navigate(`${ROUTES.BOOKINGS}?tab=pnr&pnr=${summary.pnr}`);
                    } else {
                      navigate(ROUTES.BOOKINGS);
                    }
                  }}
                >
                  View Confirmed Booking
                  <ArrowRight size={15} />
                </Button>
                <Button
                  variant="secondary"
                  size="md"
                  onClick={() => {
                    onClose();
                    navigate(ROUTES.BOOKINGS);
                  }}
                >
                  Go to My Bookings
                </Button>
              </div>
            </div>
          ) : resultState === 'failed' && paymentResult ? (
            /* ─── FAILURE RESULT SCREEN ─── */
            <div className="text-center py-4 space-y-4 animate-in fade-in">
              <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-rose-50 text-rose-600 shadow-inner">
                <XCircle size={32} />
              </div>
              <div>
                <h4 className="text-xl font-bold text-ink-primary">Simulated Payment Declined</h4>
                <p className="text-xs text-rose-700 mt-1 font-medium">
                  {paymentResult.message || 'Payment simulation failed.'}
                </p>
              </div>

              <div className="mx-auto max-w-md rounded-xl border border-rose-200 bg-rose-50/40 p-4 text-left text-xs space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-ink-muted">Transaction Reference:</span>
                  <span className="font-mono text-ink-secondary">{paymentResult.transactionRef}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-ink-muted">Payment Status:</span>
                  <Badge variant="danger" size="sm">FAILED</Badge>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-ink-muted">Booking State:</span>
                  <span className="font-semibold text-amber-800">PENDING_PAYMENT (Eligible for Retry)</span>
                </div>
                <p className="text-ink-muted pt-1 border-t border-rose-200/60 leading-relaxed">
                  Your seat allocation remains held. You can retry the payment simulation or close this window.
                </p>
              </div>

              <div className="flex flex-col sm:flex-row items-center justify-center gap-3 pt-2">
                <Button variant="accent" size="md" onClick={handleRetry}>
                  <RefreshCw size={15} />
                  Retry Payment
                </Button>
                <Button variant="secondary" size="md" onClick={onClose}>
                  Close
                </Button>
              </div>
            </div>
          ) : (
            /* ─── MAIN PAYMENT SELECTION & SUMMARY ─── */
            <div className="space-y-4">
              {errorMessage && (
                <div
                  role="alert"
                  className="rounded-xl border border-status-danger-border bg-status-danger-bg p-3.5 text-xs text-status-danger-text flex items-center gap-2"
                >
                  <AlertCircle size={16} className="shrink-0" />
                  <span>{errorMessage}</span>
                </div>
              )}

              {/* Journey & Seat Summary */}
              <div className="rounded-xl border border-surface-border bg-surface-subtle/30 p-4 space-y-3">
                <div className="flex items-center justify-between border-b border-surface-border/60 pb-2">
                  <span className="text-xs text-ink-muted">Booking Reference</span>
                  <span className="font-mono text-xs font-bold text-navy-800 bg-slate-100 px-2 py-0.5 rounded">
                    {summary?.bookingRef || bookingRef}
                  </span>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
                  <div>
                    <span className="text-ink-muted flex items-center gap-1">
                      <Train size={12} className="text-teal-600" />
                      Train
                    </span>
                    <p className="font-bold text-ink-primary mt-0.5">
                      {summary?.trainNumber} — {summary?.trainName}
                    </p>
                  </div>

                  <div>
                    <span className="text-ink-muted flex items-center gap-1">
                      <Calendar size={12} className="text-teal-600" />
                      Journey Date
                    </span>
                    <p className="font-semibold text-ink-primary mt-0.5">
                      {summary?.journeyDate || 'Scheduled'}
                    </p>
                  </div>

                  <div>
                    <span className="text-ink-muted flex items-center gap-1">
                      <Clock size={12} className="text-teal-600" />
                      Route & Timing
                    </span>
                    <p className="font-semibold text-ink-primary mt-0.5">
                      {summary?.fromStationCode} ({summary?.departureTime || '—'}) → {summary?.toStationCode} ({summary?.arrivalTime || '—'})
                    </p>
                  </div>

                  <div>
                    <span className="text-ink-muted">Allocated Berth</span>
                    <p className="font-mono font-bold text-navy-700 mt-0.5">
                      Coach {summary?.coachNumber || '—'}, Seat #{summary?.seatNumber ?? '—'}
                      {summary?.seatType ? ` (${summary.seatType})` : ''}
                    </p>
                  </div>
                </div>

                {/* Fare Row */}
                <div className="flex items-center justify-between border-t border-surface-border/60 pt-3">
                  <div>
                    <span className="text-xs font-semibold text-ink-primary">Persisted Booking Fare:</span>
                    <p className="text-2xs text-ink-muted">Verified server fare</p>
                  </div>
                  <span className="font-mono text-lg font-extrabold text-teal-700">
                    {formattedAmount}
                  </span>
                </div>
              </div>

              {/* Authoritative Seat Hold Remaining */}
              {secondsRemaining !== null && (
                <div className="flex items-center justify-between rounded-lg bg-teal-50/60 border border-teal-200 px-3 py-2 text-xs text-teal-800">
                  <span className="flex items-center gap-1.5 font-medium">
                    <Clock size={13} className="text-teal-600" />
                    Seat Hold Remaining:
                  </span>
                  <span className="font-mono font-bold text-teal-700">
                    {formatTimer(secondsRemaining)}
                  </span>
                </div>
              )}

              {/* REALISTIC PAYMENT METHOD TABS */}
              <div className="rounded-xl border border-surface-border bg-surface-card overflow-hidden">
                <div className="grid grid-cols-4 border-b border-surface-border bg-surface-subtle/30 text-2xs sm:text-xs font-semibold">
                  <button
                    type="button"
                    onClick={() => setActiveTab('upi')}
                    className={`py-2.5 px-1 flex flex-col sm:flex-row items-center justify-center gap-1 sm:gap-1.5 transition-colors ${
                      activeTab === 'upi'
                        ? 'border-b-2 border-teal-600 bg-surface-card text-teal-700'
                        : 'text-ink-muted hover:text-ink-primary'
                    }`}
                  >
                    <Smartphone size={14} />
                    <span>UPI</span>
                  </button>

                  <button
                    type="button"
                    onClick={() => setActiveTab('card')}
                    className={`py-2.5 px-1 flex flex-col sm:flex-row items-center justify-center gap-1 sm:gap-1.5 transition-colors ${
                      activeTab === 'card'
                        ? 'border-b-2 border-teal-600 bg-surface-card text-teal-700'
                        : 'text-ink-muted hover:text-ink-primary'
                    }`}
                  >
                    <CreditCard size={14} />
                    <span>Cards</span>
                  </button>

                  <button
                    type="button"
                    onClick={() => setActiveTab('netbanking')}
                    className={`py-2.5 px-1 flex flex-col sm:flex-row items-center justify-center gap-1 sm:gap-1.5 transition-colors ${
                      activeTab === 'netbanking'
                        ? 'border-b-2 border-teal-600 bg-surface-card text-teal-700'
                        : 'text-ink-muted hover:text-ink-primary'
                    }`}
                  >
                    <Building2 size={14} />
                    <span className="truncate">Net Banking</span>
                  </button>

                  <button
                    type="button"
                    onClick={() => setActiveTab('wallet')}
                    className={`py-2.5 px-1 flex flex-col sm:flex-row items-center justify-center gap-1 sm:gap-1.5 transition-colors ${
                      activeTab === 'wallet'
                        ? 'border-b-2 border-teal-600 bg-surface-card text-teal-700'
                        : 'text-ink-muted hover:text-ink-primary'
                    }`}
                  >
                    <Wallet size={14} />
                    <span>Wallet</span>
                  </button>
                </div>

                {/* Tab 1: UPI */}
                {activeTab === 'upi' && (
                  <div className="p-4 space-y-3.5">
                    <div className="flex flex-col sm:flex-row items-center gap-4 bg-surface-subtle/20 p-3 rounded-xl border border-surface-border/50">
                      <div className="flex flex-col items-center justify-center p-2.5 bg-white rounded-lg border border-slate-200 shadow-2xs">
                        <QrCode size={64} className="text-slate-800" />
                        <span className="text-[10px] font-mono text-slate-500 mt-1">Scan QR Code</span>
                      </div>
                      <div className="text-center sm:text-left space-y-1 text-xs">
                        <p className="font-bold text-ink-primary">Scan with any UPI App</p>
                        <p className="text-2xs text-ink-muted leading-relaxed">
                          Google Pay, PhonePe, Paytm, or BHIM. Simulation authorizes instantly without bank debit.
                        </p>
                        <div className="flex flex-wrap gap-1.5 pt-1 justify-center sm:justify-start">
                          <span className="px-2 py-0.5 rounded text-[10px] font-medium bg-slate-100 text-slate-700">GPay</span>
                          <span className="px-2 py-0.5 rounded text-[10px] font-medium bg-purple-50 text-purple-700">PhonePe</span>
                          <span className="px-2 py-0.5 rounded text-[10px] font-medium bg-sky-50 text-sky-700">Paytm</span>
                          <span className="px-2 py-0.5 rounded text-[10px] font-medium bg-emerald-50 text-emerald-700">BHIM</span>
                        </div>
                      </div>
                    </div>

                    <div className="space-y-1.5 text-xs">
                      <label htmlFor="upi-vpa-input" className="block font-semibold text-ink-secondary">
                        Or enter Test UPI ID
                      </label>
                      <div className="flex gap-2">
                        <input
                          id="upi-vpa-input"
                          type="text"
                          value={upiId}
                          onChange={(e) => setUpiId(e.target.value)}
                          placeholder="e.g. yourname@okhdfcbank"
                          className="flex-1 rounded-lg border border-surface-border bg-surface-base px-3 py-2 text-xs font-mono text-ink-primary focus:border-teal-500 focus:outline-none"
                        />
                        <button
                          type="button"
                          onClick={() => setUpiId('railora@demoupi')}
                          className="px-2.5 py-1.5 text-2xs font-semibold rounded-lg bg-teal-50 text-teal-700 hover:bg-teal-100 transition-colors"
                        >
                          Use Test VPA
                        </button>
                      </div>
                    </div>
                  </div>
                )}

                {/* Tab 2: Cards */}
                {activeTab === 'card' && (
                  <div className="p-4 space-y-3.5">
                    {/* Simulated Card Preview */}
                    <div className="bg-gradient-to-r from-navy-800 to-navy-950 text-white p-3.5 rounded-xl shadow-sm text-xs space-y-2">
                      <div className="flex items-center justify-between text-2xs opacity-80">
                        <span>Demo Debit / Credit Card</span>
                        <span className="font-bold tracking-wider">RuPay / VISA</span>
                      </div>
                      <p className="font-mono text-sm tracking-widest">{cardNumber}</p>
                      <div className="flex items-center justify-between text-2xs pt-1 opacity-90">
                        <div>
                          <span className="text-[9px] block opacity-70">CARDHOLDER</span>
                          <span className="font-medium">{cardHolder}</span>
                        </div>
                        <div>
                          <span className="text-[9px] block opacity-70">EXPIRES</span>
                          <span className="font-mono">{cardExpiry}</span>
                        </div>
                      </div>
                    </div>

                    <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5 text-xs">
                      <div className="col-span-2 sm:col-span-3">
                        <label className="block text-2xs font-semibold text-ink-secondary mb-1">
                          Card Number (Demo only)
                        </label>
                        <input
                          type="text"
                          value={cardNumber}
                          onChange={(e) => setCardNumber(e.target.value)}
                          className="w-full rounded-lg border border-surface-border bg-surface-base px-3 py-1.5 text-xs font-mono"
                        />
                      </div>
                      <div>
                        <label className="block text-2xs font-semibold text-ink-secondary mb-1">
                          Expiry
                        </label>
                        <input
                          type="text"
                          value={cardExpiry}
                          onChange={(e) => setCardExpiry(e.target.value)}
                          className="w-full rounded-lg border border-surface-border bg-surface-base px-3 py-1.5 text-xs font-mono"
                        />
                      </div>
                      <div>
                        <label className="block text-2xs font-semibold text-ink-secondary mb-1">
                          CVV
                        </label>
                        <input
                          type="password"
                          value={cardCvv}
                          maxLength={3}
                          onChange={(e) => setCardCvv(e.target.value)}
                          className="w-full rounded-lg border border-surface-border bg-surface-base px-3 py-1.5 text-xs font-mono"
                        />
                      </div>
                      <div className="col-span-2 sm:col-span-1 flex items-end">
                        <button
                          type="button"
                          onClick={() => {
                            setCardNumber('4532 •••• •••• 8891');
                            setCardExpiry('08/29');
                            setCardCvv('742');
                            setCardHolder('Demo Traveler');
                          }}
                          className="w-full py-1.5 text-2xs font-semibold rounded-lg bg-teal-50 text-teal-700 hover:bg-teal-100 transition-colors"
                        >
                          Auto-fill Demo Card
                        </button>
                      </div>
                    </div>
                  </div>
                )}

                {/* Tab 3: Net Banking */}
                {activeTab === 'netbanking' && (
                  <div className="p-4 space-y-3">
                    <p className="text-2xs font-semibold uppercase text-ink-muted">
                      Select Popular Bank
                    </p>
                    <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
                      {POPULAR_BANKS.map((b) => (
                        <button
                          key={b.code}
                          type="button"
                          onClick={() => setSelectedBank(b.code)}
                          className={`flex items-center justify-between p-2.5 rounded-lg border text-xs font-medium transition-all ${
                            selectedBank === b.code
                              ? 'border-teal-600 bg-teal-50/60 text-teal-800 shadow-2xs'
                              : 'border-surface-border hover:bg-surface-subtle text-ink-primary'
                          }`}
                        >
                          <span className="truncate">{b.name}</span>
                          {selectedBank === b.code && (
                            <Check size={14} className="text-teal-600 shrink-0 ml-1" />
                          )}
                        </button>
                      ))}
                    </div>
                  </div>
                )}

                {/* Tab 4: Wallet */}
                {activeTab === 'wallet' && (
                  <div className="p-4 space-y-3">
                    <div className="flex items-center justify-between p-3 rounded-xl bg-teal-50 border border-teal-200">
                      <div className="flex items-center gap-2.5">
                        <Wallet size={20} className="text-teal-700" />
                        <div>
                          <p className="text-xs font-bold text-teal-900">Railora Demo Wallet</p>
                          <p className="text-2xs text-teal-700">Preloaded simulated balance</p>
                        </div>
                      </div>
                      <span className="font-mono text-sm font-bold text-teal-800">
                        ₹10,000.00
                      </span>
                    </div>
                    <p className="text-2xs text-ink-muted leading-relaxed">
                      Instant 1-click test checkout with zero simulated network delay. Recommended for swift reservation testing.
                    </p>
                  </div>
                )}
              </div>

              {/* Attempt Details info */}
              <div className="text-2xs font-mono text-ink-muted flex items-center justify-between px-1">
                <span>Attempt #{attemptNumber}</span>
                <span className="truncate max-w-[240px]">Key: {idempotencyKey}</span>
              </div>
            </div>
          )}
        </div>

        {/* Sticky Footer / Action Buttons */}
        {resultState === 'idle' && !loadingSummary && !summaryError && (
          <div className="flex-shrink-0 border-t border-surface-border p-4 bg-surface-card/95 backdrop-blur-xs space-y-2.5">
            <div className="flex items-center justify-between text-xs pb-1">
              <span className="flex items-center gap-1.5 text-2xs text-ink-muted">
                <ShieldCheck size={14} className="text-teal-600" />
                Simulated Gateway • No Real Charge
              </span>
              <span className="font-mono font-bold text-ink-primary">
                Total: {formattedAmount}
              </span>
            </div>

            {/* TWO CLEARLY SEPARATED ACTIONS (Strict Requirement) */}
            <div className="space-y-2">
              <Button
                variant="accent"
                size="lg"
                className="w-full justify-center text-sm font-semibold shadow-sm"
                onClick={() => {
                  if (secondsRemaining === 0 || actionLoading !== null) return;
                  setShowConfirmDialog(true);
                }}
                isLoading={actionLoading === 'success'}
                disabled={actionLoading !== null || secondsRemaining === 0}
              >
                <CheckCircle2 size={16} />
                Simulate Successful Payment
              </Button>

              <Button
                variant="danger"
                size="md"
                className="w-full justify-center text-sm font-semibold"
                onClick={handleSimulateFailure}
                isLoading={actionLoading === 'failure'}
                disabled={actionLoading !== null || secondsRemaining === 0}
              >
                <XCircle size={16} />
                Simulate Failed Payment
              </Button>
            </div>

            <div className="flex justify-end pt-1">
              <Button
                variant="ghost"
                size="sm"
                onClick={onClose}
                disabled={actionLoading !== null}
              >
                Cancel
              </Button>
            </div>
          </div>
        )}

        {/* Payment Confirmation Dialog */}
        {showConfirmDialog && (
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="confirm-payment-title"
            className="fixed inset-0 z-[70] flex items-center justify-center p-4 bg-navy-950/80 backdrop-blur-xs animate-in fade-in"
          >
            <div className="relative w-full max-w-sm rounded-2xl border border-surface-border bg-surface-card p-6 shadow-2xl text-center space-y-4">
              <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-teal-50 text-teal-600">
                <CreditCard size={24} />
              </div>
              <div className="space-y-1.5">
                <h4 id="confirm-payment-title" className="text-base font-bold text-ink-primary">
                  Confirm Payment
                </h4>
                <p className="text-sm font-semibold text-ink-primary">
                  Are you sure you want to pay {formattedAmount}?
                </p>
                <p className="text-2xs text-ink-muted">
                  Simulated demo transaction. Your seat allocation will be confirmed upon authorization.
                </p>
              </div>

              <div className="flex items-center justify-center gap-3 pt-2">
                <Button
                  variant="secondary"
                  size="md"
                  onClick={() => setShowConfirmDialog(false)}
                  disabled={actionLoading !== null}
                >
                  Cancel
                </Button>
                <Button
                  variant="accent"
                  size="md"
                  onClick={() => {
                    setShowConfirmDialog(false);
                    void handleSimulateSuccess();
                  }}
                  isLoading={actionLoading === 'success'}
                  disabled={actionLoading !== null}
                >
                  OK, Pay {formattedAmount}
                </Button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
