import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  Train,
  Calendar,
  Clock,
  Ticket,
  CreditCard,
  CheckCircle2,
  AlertCircle,
  ArrowRight,
  RefreshCw,
  User,
} from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Badge';
import { Card, CardContent } from '@/components/ui/Card';
import { useAuth } from '@/features/auth/AuthContext';
import { bookingApi } from '@/features/booking/api';
import { clearPaymentAttempt, getOrCreatePaymentAttempt } from '@/features/booking/paymentAttempt';
import type { BookingDetailResponseDTO } from '@/features/booking/types';
import { ROUTES } from '@/lib/constants';

export default function BookingsPage() {
  const { state: authState } = useAuth();
  const navigate = useNavigate();

  const [bookings, setBookings] = useState<BookingDetailResponseDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [payingRef, setPayingRef] = useState<string | null>(null);
  const [cancellingRef, setCancellingRef] = useState<string | null>(null);
  const [confirmCancelBooking, setConfirmCancelBooking] = useState<BookingDetailResponseDTO | null>(null);
  const paymentsInFlight = useRef(new Set<string>());

  async function loadBookings() {
    if (authState.status !== 'authenticated') {
      setLoading(false);
      return;
    }

    setLoading(true);
    setError(null);
    try {
      const response = await bookingApi.getBookingHistory(0, 20);
      setBookings(response.content || []);
    } catch {
      setError('Unable to load your bookings right now. Please try again.');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadBookings();
  }, [authState.status]);

  async function handleCompletePayment(bookingRef: string) {
    if (paymentsInFlight.current.has(bookingRef)) return;
    paymentsInFlight.current.add(bookingRef);
    setPayingRef(bookingRef);
    setError(null);
    try {
      const attempt = getOrCreatePaymentAttempt(bookingRef, false);
      const res = await bookingApi.payBooking(bookingRef, attempt);

      if (res.paymentStatus === 'FAILED') {
        clearPaymentAttempt(bookingRef);
        setError(`Payment for ${bookingRef} failed: ${res.message || 'Transaction was declined.'}`);
        if (res.bookingStatus !== 'PENDING_PAYMENT') {
          await loadBookings();
        }
      } else {
        clearPaymentAttempt(bookingRef);
        await loadBookings();
      }
    } catch (err: unknown) {
      const msg =
        err && typeof err === 'object' && 'response' in err
          ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
          : null;
      setError(
        msg ||
          (err instanceof Error ? err.message : null) ||
          'Payment processing failed. Please check your connection and try again.',
      );
    } finally {
      paymentsInFlight.current.delete(bookingRef);
      setPayingRef(null);
    }
  }

  async function handleConfirmCancel(bookingRef: string) {
    setCancellingRef(bookingRef);
    setError(null);
    setSuccessMessage(null);
    try {
      await bookingApi.cancelBooking(bookingRef);
      setSuccessMessage(`Booking ${bookingRef} has been cancelled successfully. Seat allocation has been released.`);
      setConfirmCancelBooking(null);
      await loadBookings();
    } catch (err: unknown) {
      const msg =
        err && typeof err === 'object' && 'response' in err
          ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
          : null;
      setError(
        msg ||
          (err instanceof Error ? err.message : null) ||
          'Failed to cancel booking. Please try again.',
      );
      setConfirmCancelBooking(null);
    } finally {
      setCancellingRef(null);
    }
  }

  if (authState.status === 'unauthenticated') {
    return (
      <section className="mx-auto max-w-4xl px-4 py-16 sm:px-6 lg:px-8">
        <div className="rounded-2xl border border-surface-border bg-surface-card p-8 sm:p-12 text-center shadow-card">
          <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-teal-50 text-teal-600 mb-4 shadow-sm">
            <User size={28} />
          </div>
          <h1 className="text-2xl sm:text-3xl font-bold text-ink-primary">Sign in to view your bookings</h1>
          <p className="mx-auto mt-3 max-w-md text-sm text-ink-muted">
            Access your confirmed railway tickets, monitor reservation statuses, and view complete trip details.
          </p>
          <div className="mt-6 flex items-center justify-center gap-3">
            <Button variant="accent" size="lg" onClick={() => navigate(ROUTES.LOGIN)}>
              Sign In
              <ArrowRight size={16} />
            </Button>
            <Button variant="secondary" size="lg" onClick={() => navigate(ROUTES.REGISTER)}>
              Create Account
            </Button>
          </div>
        </div>
      </section>
    );
  }

  return (
    <section className="mx-auto max-w-6xl px-4 py-8 sm:px-6 lg:px-8">
      {/* Header bar */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 border-b border-surface-border pb-6">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-2xl sm:text-3xl font-extrabold text-navy-700 tracking-tight">
              My Bookings
            </h1>
            <Badge variant="teal" size="sm">
              {bookings.length} {bookings.length === 1 ? 'Trip' : 'Trips'}
            </Badge>
          </div>
          <p className="mt-1 text-sm text-ink-muted">
            Manage your booked train journeys, seat allocations, and payment confirmations.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Button
            variant="secondary"
            size="sm"
            onClick={loadBookings}
            disabled={loading}
            aria-label="Refresh bookings"
          >
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
            Refresh
          </Button>
          <Link to={ROUTES.SEARCH}>
            <Button variant="accent" size="sm">
              <Train size={14} />
              Book Another Train
            </Button>
          </Link>
        </div>
      </div>

      {error && (
        <div
          role="alert"
          className="mt-6 flex items-center justify-between rounded-xl border border-status-danger-border bg-status-danger-bg p-4 text-sm text-status-danger-text"
        >
          <div className="flex items-center gap-2">
            <AlertCircle size={18} className="shrink-0" />
            <span>{error}</span>
          </div>
          <button
            type="button"
            onClick={() => setError(null)}
            className="text-status-danger-text/80 hover:text-status-danger-text text-xs font-semibold underline"
          >
            Dismiss
          </button>
        </div>
      )}

      {successMessage && (
        <div
          role="status"
          className="mt-6 flex items-center justify-between rounded-xl border border-teal-200 bg-teal-50 p-4 text-sm text-teal-800"
        >
          <div className="flex items-center gap-2">
            <CheckCircle2 size={18} className="text-teal-600 shrink-0" />
            <span>{successMessage}</span>
          </div>
          <button
            type="button"
            onClick={() => setSuccessMessage(null)}
            className="text-teal-700/80 hover:text-teal-800 text-xs font-semibold underline"
          >
            Dismiss
          </button>
        </div>
      )}

      {loading ? (
        <div className="py-20 text-center">
          <div className="mx-auto h-8 w-8 animate-spin rounded-full border-2 border-teal-600 border-t-transparent" />
          <p className="mt-3 text-sm text-ink-muted">Loading your bookings…</p>
        </div>
      ) : bookings.length === 0 ? (
        <div className="mt-12 rounded-2xl border border-dashed border-surface-border p-12 text-center bg-surface-card">
          <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-xl bg-slate-100 text-slate-500 mb-3">
            <Ticket size={24} />
          </div>
          <h2 className="text-lg font-bold text-ink-primary">No bookings found</h2>
          <p className="mt-1 text-sm text-ink-muted">
            You don't have any booked journeys yet. Search available trains to make your first reservation.
          </p>
          <div className="mt-6">
            <Link to={ROUTES.SEARCH}>
              <Button variant="accent" size="md">
                Search Trains
                <ArrowRight size={15} />
              </Button>
            </Link>
          </div>
        </div>
      ) : (
        <div className="mt-6 space-y-4">
          {bookings.map((b) => {
            const isConfirmed = b.bookingStatus === 'CONFIRMED';
            const isPendingPayment = b.bookingStatus === 'PENDING_PAYMENT';
            const isCancelled = b.bookingStatus === 'CANCELLED';

            return (
              <Card
                key={b.bookingId}
                className="overflow-hidden border border-surface-border hover:shadow-card transition-interact"
              >
                <CardContent className="p-5 sm:p-6">
                  {/* Card top banner */}
                  <div className="flex flex-wrap items-center justify-between gap-3 border-b border-surface-border/60 pb-4">
                    <div className="flex items-center gap-3">
                      <span className="font-mono text-xs font-bold text-navy-700 bg-navy-50 border border-navy-200 px-2.5 py-1 rounded-md">
                        PNR: {b.bookingRef}
                      </span>
                      <span className="font-bold text-ink-primary text-base">
                        {b.trainNumber} — {b.trainName}
                      </span>
                    </div>

                    <div className="flex items-center gap-2">
                      <Badge
                        variant={isConfirmed ? 'teal' : isPendingPayment ? 'navy' : isCancelled ? 'danger' : 'neutral'}
                        size="sm"
                      >
                        {b.bookingStatus}
                      </Badge>
                      <span className="text-2xs font-mono font-medium uppercase px-2 py-0.5 rounded bg-slate-100 text-slate-600">
                        {b.paymentStatus}
                      </span>
                    </div>
                  </div>

                  {/* Route & Seat Details */}
                  <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-12 sm:items-center">
                    {/* From Station */}
                    <div className="sm:col-span-3">
                      <span className="text-xs text-ink-muted">Origin</span>
                      <div className="flex items-baseline gap-2 mt-0.5">
                        <span className="font-mono text-base font-bold text-navy-700">
                          {b.fromStationCode}
                        </span>
                        <span className="text-xs text-ink-secondary truncate">
                          {b.fromStationName}
                        </span>
                      </div>
                      <div className="flex items-center gap-1 text-xs text-ink-muted mt-1">
                        <Clock size={12} className="text-teal-600" />
                        <span>{b.departureTime}</span>
                      </div>
                    </div>

                    {/* Middle Line */}
                    <div className="sm:col-span-3 flex flex-col items-center justify-center">
                      <div className="flex items-center gap-1 text-2xs text-ink-muted">
                        <Calendar size={12} className="text-teal-600" />
                        <span>{b.journeyDate}</span>
                      </div>
                      <div className="relative flex w-full max-w-[120px] items-center my-1.5">
                        <div className="h-2 w-2 rounded-full border border-teal-600 bg-teal-600" />
                        <div className="h-0.5 flex-1 bg-surface-subtle mx-1" />
                        <ArrowRight size={12} className="text-teal-600" />
                        <div className="h-2 w-2 rounded-full border border-navy-700 bg-navy-700" />
                      </div>
                    </div>

                    {/* To Station */}
                    <div className="sm:col-span-3">
                      <span className="text-xs text-ink-muted">Destination</span>
                      <div className="flex items-baseline gap-2 mt-0.5">
                        <span className="font-mono text-base font-bold text-navy-700">
                          {b.toStationCode}
                        </span>
                        <span className="text-xs text-ink-secondary truncate">
                          {b.toStationName}
                        </span>
                      </div>
                      <div className="flex items-center gap-1 text-xs text-ink-muted mt-1">
                        <Clock size={12} className="text-teal-600" />
                        <span>{b.arrivalTime}</span>
                      </div>
                    </div>

                    {/* Berth & Fare details */}
                    <div className="sm:col-span-3 sm:border-l sm:border-surface-border/60 sm:pl-4 flex flex-col justify-center">
                      <div className="flex items-center justify-between sm:justify-start sm:gap-2">
                        <span className="text-xs text-ink-muted">Berth:</span>
                        <span className="font-mono text-sm font-bold text-navy-700">
                          Coach {b.coachNumber}, #{b.seatNumber}
                        </span>
                      </div>
                      <div className="flex items-center justify-between sm:justify-start sm:gap-2 mt-1">
                        <span className="text-xs text-ink-muted">Amount:</span>
                        <span className="font-mono text-sm font-bold text-teal-700">
                          ₹{b.amount?.toFixed(2) ?? '500.00'}
                        </span>
                      </div>
                    </div>
                  </div>

                  {/* Pending payment action */}
                  {isPendingPayment && (
                    <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-surface-border/60 pt-3 bg-amber-50/50 -mx-5 -mb-5 px-5 py-3">
                      <span className="text-xs text-amber-800 flex items-center gap-1.5">
                        <AlertCircle size={14} className="text-amber-600" />
                        Payment pending for this reserved seat. Complete payment to secure booking.
                      </span>
                      <div className="flex items-center gap-2">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => setConfirmCancelBooking(b)}
                          aria-label={`Cancel booking ${b.bookingRef}`}
                        >
                          Cancel Booking
                        </Button>
                        <Button
                          variant="accent"
                          size="sm"
                          onClick={() => handleCompletePayment(b.bookingRef)}
                          isLoading={payingRef === b.bookingRef}
                        >
                          <CreditCard size={14} />
                          Pay Now
                        </Button>
                      </div>
                    </div>
                  )}

                  {isConfirmed && (
                    <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-surface-border/40 pt-3">
                      <div className="flex items-center gap-1 text-2xs text-teal-700">
                        <CheckCircle2 size={12} />
                        Confirmed reservation. Carry valid photo ID during travel.
                      </div>
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => setConfirmCancelBooking(b)}
                        aria-label={`Cancel booking ${b.bookingRef}`}
                      >
                        Cancel Ticket
                      </Button>
                    </div>
                  )}

                  {isCancelled && (
                    <div className="mt-4 flex items-center gap-1.5 text-2xs text-rose-700 border-t border-surface-border/40 pt-2 font-medium">
                      <AlertCircle size={13} className="text-rose-600 shrink-0" />
                      This booking has been cancelled and the reserved seat has been released.
                    </div>
                  )}
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}

      {/* Cancellation Confirmation Modal */}
      {confirmCancelBooking && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="cancel-modal-title"
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-navy-950/60 backdrop-blur-xs animate-in fade-in"
        >
          <div className="relative w-full max-w-lg rounded-2xl border border-surface-border bg-surface-card p-6 shadow-modal">
            <div className="flex items-center justify-between border-b border-surface-border/60 pb-3">
              <div className="flex items-center gap-2 text-rose-600">
                <AlertCircle size={20} />
                <h3 id="cancel-modal-title" className="text-lg font-bold text-ink-primary">
                  Confirm Cancellation
                </h3>
              </div>
              <button
                type="button"
                onClick={() => setConfirmCancelBooking(null)}
                className="text-ink-muted hover:text-ink-primary p-1 rounded-md text-sm font-bold"
                aria-label="Close cancellation modal"
              >
                ✕
              </button>
            </div>

            <div className="mt-4 space-y-3 text-sm">
              <p className="text-ink-secondary">
                Are you sure you want to cancel your reservation for{' '}
                <strong className="text-ink-primary">
                  {confirmCancelBooking.trainName} ({confirmCancelBooking.trainNumber})
                </strong>
                ?
              </p>

              <div className="rounded-xl border border-surface-border bg-surface-muted p-3 text-xs space-y-1.5 font-mono text-ink-secondary">
                <div>
                  PNR: <strong className="text-navy-700">{confirmCancelBooking.bookingRef}</strong>
                </div>
                <div>
                  Route: {confirmCancelBooking.fromStationCode} → {confirmCancelBooking.toStationCode} on{' '}
                  {confirmCancelBooking.journeyDate}
                </div>
                <div>
                  Berth: Coach {confirmCancelBooking.coachNumber}, #{confirmCancelBooking.seatNumber} (
                  {confirmCancelBooking.seatType})
                </div>
              </div>

              {confirmCancelBooking.bookingStatus === 'CONFIRMED' && (
                <div className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900">
                  <div className="font-semibold flex items-center gap-1.5 mb-1">
                    <AlertCircle size={14} className="text-amber-700 shrink-0" />
                    <span>Seat Release &amp; Refund Notice</span>
                  </div>
                  <p className="text-2xs leading-relaxed text-amber-800">
                    Your seat will be immediately released and returned to general availability for other travelers.
                    Note: Automated refunds are not supported by the mock payment gateway in this version.
                  </p>
                </div>
              )}
            </div>

            <div className="mt-6 flex items-center justify-end gap-3 border-t border-surface-border/60 pt-4">
              <Button
                variant="secondary"
                size="md"
                onClick={() => setConfirmCancelBooking(null)}
                disabled={cancellingRef !== null}
              >
                Keep Booking
              </Button>
              <Button
                variant="danger"
                size="md"
                onClick={() => handleConfirmCancel(confirmCancelBooking.bookingRef)}
                isLoading={cancellingRef === confirmCancelBooking.bookingRef}
              >
                Confirm Cancellation
              </Button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
