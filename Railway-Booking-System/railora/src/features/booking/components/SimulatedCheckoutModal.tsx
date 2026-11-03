import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  AlertCircle,
  AlertTriangle,
  ArrowRight,
  Calendar,
  CheckCircle2,
  Clock,
  RefreshCw,
  Ticket,
  Train,
  X,
  XCircle,
} from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Badge';
import { bookingApi } from '../api';
import type { PaymentResponseDTO, SimulatedPaymentSummaryDTO } from '../types';
import { ROUTES } from '@/lib/constants';

export interface SimulatedCheckoutModalProps {
  isOpen: boolean;
  onClose: () => void;
  bookingRef: string;
  initialSummary?: Partial<SimulatedPaymentSummaryDTO>;
  onSuccess?: (payment: PaymentResponseDTO) => void;
  onFailure?: (payment: PaymentResponseDTO) => void;
}

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

  const [actionLoading, setActionLoading] = useState<'success' | 'failure' | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const [resultState, setResultState] = useState<'idle' | 'success' | 'failed'>('idle');
  const [paymentResult, setPaymentResult] = useState<PaymentResponseDTO | null>(null);

  // Idempotency key per payment attempt (refreshed on retry)
  const [idempotencyKey, setIdempotencyKey] = useState<string>(() => `SIM-${crypto.randomUUID()}`);
  const [attemptNumber, setAttemptNumber] = useState<number>(1);

  // Seat hold countdown timer
  const [secondsRemaining, setSecondsRemaining] = useState<number | null>(null);
  const inFlightRef = useRef(false);

  useEffect(() => {
    if (!isOpen || !bookingRef) return;

    // Reset state on open
    setResultState('idle');
    setPaymentResult(null);
    setErrorMessage(null);
    setIdempotencyKey(`SIM-${crypto.randomUUID()}`);
    setAttemptNumber(1);

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

  // Handle hold expiry timer
  useEffect(() => {
    if (!summary?.holdExpiresAt) {
      setSecondsRemaining(null);
      return;
    }

    const expiryTime = new Date(summary.holdExpiresAt).getTime();
    function tick() {
      const diff = Math.max(0, Math.floor((expiryTime - Date.now()) / 1000));
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

  async function handleSimulateSuccess() {
    if (inFlightRef.current || !bookingRef) return;
    inFlightRef.current = true;
    setActionLoading('success');
    setErrorMessage(null);

    try {
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
    } finally {
      inFlightRef.current = false;
      setActionLoading(null);
    }
  }

  async function handleSimulateFailure() {
    if (inFlightRef.current || !bookingRef) return;
    inFlightRef.current = true;
    setActionLoading('failure');
    setErrorMessage(null);

    try {
      const res = await bookingApi.simulatePaymentFailure(bookingRef, {
        idempotencyKey,
        reason: 'Simulated payment failure (user selected)',
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
    } finally {
      inFlightRef.current = false;
      setActionLoading(null);
    }
  }

  function handleRetry() {
    // Generate fresh idempotency key for new attempt
    setIdempotencyKey(`SIM-${crypto.randomUUID()}`);
    setAttemptNumber((prev) => prev + 1);
    setResultState('idle');
    setPaymentResult(null);
    setErrorMessage(null);
  }

  function formatTimer(seconds: number): string {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="simulated-checkout-title"
      className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-navy-950/70 backdrop-blur-xs animate-in fade-in overflow-y-auto"
    >
      <div className="relative w-full max-w-xl rounded-2xl border border-surface-border bg-surface-card p-6 shadow-modal my-6">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-surface-border/60 pb-4">
          <div className="flex items-center gap-2.5">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-teal-50 text-teal-700">
              <Ticket size={20} />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 id="simulated-checkout-title" className="text-lg font-bold text-ink-primary">
                  Demo Checkout
                </h3>
                <Badge variant="warning" size="sm">
                  DEMO MODE
                </Badge>
              </div>
              <p className="text-xs text-ink-muted">Simulated payment environment</p>
            </div>
          </div>

          <button
            type="button"
            onClick={onClose}
            disabled={actionLoading !== null}
            className="text-ink-muted hover:text-ink-primary p-1.5 rounded-lg hover:bg-surface-subtle transition-colors disabled:opacity-50"
            aria-label="Close demo checkout modal"
          >
            <X size={18} />
          </button>
        </div>

        {/* DEMO NOTICE BANNER (Strict Requirement) */}
        <div
          role="status"
          className="mt-4 rounded-xl border border-amber-300 bg-amber-50 p-3.5 text-amber-900"
        >
          <div className="flex items-start gap-2.5">
            <AlertTriangle size={18} className="text-amber-700 shrink-0 mt-0.5" />
            <div className="text-xs space-y-1">
              <p className="font-extrabold uppercase tracking-wide text-amber-900">
                DEMO MODE — NO REAL MONEY WILL BE CHARGED.
              </p>
              <p className="text-amber-800 leading-relaxed">
                This is strictly a simulation of the payment outcome. Do not enter card numbers, UPI PINs, CVVs, or bank credentials.
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
          <div className="mt-6 rounded-xl border border-status-danger-border bg-status-danger-bg p-4 text-sm text-status-danger-text">
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
        ) : resultState === 'success' && paymentResult ? (
          /* ─── SUCCESS RESULT SCREEN ─── */
          <div className="mt-6 text-center py-4 space-y-4">
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

            <div className="mt-6 flex flex-col sm:flex-row items-center justify-center gap-3">
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
          <div className="mt-6 text-center py-4 space-y-4">
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

            <div className="mt-6 flex flex-col sm:flex-row items-center justify-center gap-3">
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
          /* ─── MAIN SUMMARY & ACTIONS ─── */
          <div className="mt-5 space-y-4">
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
                  {summary?.amount != null ? `₹${summary.amount.toFixed(2)}` : '₹500.00'}
                </span>
              </div>
            </div>

            {/* Hold Timer Info */}
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

            {/* Attempt Details info */}
            <div className="text-2xs font-mono text-ink-muted flex items-center justify-between px-1">
              <span>Attempt #{attemptNumber}</span>
              <span className="truncate max-w-[240px]">Key: {idempotencyKey}</span>
            </div>

            {/* TWO CLEARLY SEPARATED ACTIONS (Strict Requirement) */}
            <div className="pt-2 space-y-2.5 border-t border-surface-border">
              <Button
                variant="accent"
                size="lg"
                className="w-full justify-center text-sm font-semibold shadow-sm"
                onClick={handleSimulateSuccess}
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

              <div className="flex justify-end pt-2">
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
          </div>
        )}
      </div>
    </div>
  );
}
