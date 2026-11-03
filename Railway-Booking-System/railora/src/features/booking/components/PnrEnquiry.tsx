import React, { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import {
  Search,
  Ticket,
  AlertCircle,
  CheckCircle2,
  Clock,
  Calendar,
  ArrowRight,
  RefreshCw,
  X,
  CreditCard,
  User as UserIcon,
} from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Badge';
import { Card, CardContent } from '@/components/ui/Card';
import { bookingApi } from '@/features/booking/api';
import type { BookingDetailResponseDTO } from '@/features/booking/types';
import { ROUTES } from '@/lib/constants';

export interface PnrEnquiryProps {
  initialPnr?: string;
  onClear?: () => void;
}

export function PnrEnquiry({ initialPnr, onClear }: PnrEnquiryProps) {
  const [pnrInput, setPnrInput] = useState(initialPnr || '');
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [apiError, setApiError] = useState<{ status?: number; message: string } | null>(null);
  const [booking, setBooking] = useState<BookingDetailResponseDTO | null>(null);

  // Auto-search if initialPnr is passed and valid
  useEffect(() => {
    if (initialPnr && /^\d{10}$/.test(initialPnr.trim())) {
      setPnrInput(initialPnr.trim());
      void handleSearch(initialPnr.trim());
    }
  }, [initialPnr]);

  function validate(value: string): string | null {
    const trimmed = value.trim();
    if (!trimmed) {
      return 'Please enter a 10-digit PNR number.';
    }
    if (!/^\d+$/.test(trimmed)) {
      return 'PNR must contain only numbers.';
    }
    if (trimmed.length !== 10) {
      return `PNR must be exactly 10 digits (currently ${trimmed.length}).`;
    }
    return null;
  }

  async function handleSearch(targetPnr?: string) {
    const valueToSearch = (targetPnr ?? pnrInput).trim();
    const validationError = validate(valueToSearch);
    if (validationError) {
      setFieldError(validationError);
      setBooking(null);
      setApiError(null);
      return;
    }

    setFieldError(null);
    setApiError(null);
    setLoading(true);

    try {
      const data = await bookingApi.getBookingByPnr(valueToSearch);
      setBooking(data);
    } catch (err: unknown) {
      setBooking(null);
      const axiosError = err as { response?: { status?: number; data?: { message?: string; error?: string } } };
      const status = axiosError?.response?.status;
      const backendMessage = axiosError?.response?.data?.message || axiosError?.response?.data?.error;

      if (status === 400) {
        setApiError({
          status: 400,
          message: backendMessage || 'Invalid PNR format. PNR must be exactly 10 numeric digits.',
        });
      } else if (status === 401) {
        setApiError({
          status: 401,
          message: 'Please sign in to check your booking PNR status.',
        });
      } else if (status === 403) {
        setApiError({
          status: 403,
          message: backendMessage || 'You are not authorized to view this booking.',
        });
      } else if (status === 404) {
        setApiError({
          status: 404,
          message: backendMessage || `No booking found for PNR "${valueToSearch}". Please check the number and verify it belongs to your account.`,
        });
      } else {
        setApiError({
          status: status || 500,
          message: backendMessage || 'Unable to retrieve booking details right now. Please check your connection and try again.',
        });
      }
    } finally {
      setLoading(false);
    }
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    void handleSearch();
  }

  function handleReset() {
    setPnrInput('');
    setFieldError(null);
    setApiError(null);
    setBooking(null);
    if (onClear) onClear();
  }

  return (
    <div className="space-y-6">
      {/* Search Input Box */}
      <Card className="border border-surface-border shadow-card bg-surface-card overflow-hidden">
        <CardContent className="p-5 sm:p-6">
          <form onSubmit={handleSubmit} role="search" aria-label="PNR Status Enquiry" className="space-y-4">
            <div>
              <label
                htmlFor="pnr-input"
                className="block text-xs font-semibold uppercase tracking-wider text-ink-secondary mb-1.5"
              >
                Enter 10-Digit PNR Number
              </label>
              <div className="flex flex-col sm:flex-row gap-3">
                <div className="relative flex-1">
                  <div className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-muted">
                    <Ticket size={18} />
                  </div>
                  <input
                    id="pnr-input"
                    type="text"
                    inputMode="numeric"
                    pattern="[0-9]*"
                    maxLength={10}
                    value={pnrInput}
                    onChange={(e) => {
                      const val = e.target.value.replace(/\D/g, '').slice(0, 10);
                      setPnrInput(val);
                      if (fieldError) setFieldError(null);
                    }}
                    placeholder="e.g. 9847291842"
                    disabled={loading}
                    aria-invalid={Boolean(fieldError)}
                    aria-describedby={fieldError ? 'pnr-error' : undefined}
                    className="w-full rounded-xl border border-surface-border bg-surface-base pl-10 pr-10 py-2.5 text-sm font-mono text-ink-primary shadow-xs transition-interact placeholder:text-ink-muted/60 focus:border-teal-500 focus:outline-none focus:ring-2 focus:ring-teal-500/20 disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {pnrInput && !loading && (
                    <button
                      type="button"
                      onClick={handleReset}
                      aria-label="Clear PNR input"
                      className="absolute right-3 top-1/2 -translate-y-1/2 text-ink-muted hover:text-ink-primary p-0.5 rounded transition-colors"
                    >
                      <X size={16} />
                    </button>
                  )}
                </div>

                <div className="flex gap-2">
                  <Button
                    type="submit"
                    variant="accent"
                    size="md"
                    disabled={loading}
                    isLoading={loading}
                    className="min-w-[130px]"
                  >
                    <Search size={16} />
                    Check Status
                  </Button>

                  {(booking || apiError) && !loading && (
                    <Button
                      type="button"
                      variant="secondary"
                      size="md"
                      onClick={handleReset}
                    >
                      Clear
                    </Button>
                  )}
                </div>
              </div>

              {fieldError && (
                <p id="pnr-error" role="alert" className="mt-2 text-xs font-medium text-rose-600 flex items-center gap-1">
                  <AlertCircle size={14} className="shrink-0" />
                  {fieldError}
                </p>
              )}
            </div>
          </form>
        </CardContent>
      </Card>

      {/* Loading Indicator */}
      {loading && (
        <div className="py-12 text-center" aria-live="polite" aria-busy="true">
          <div className="mx-auto h-8 w-8 animate-spin rounded-full border-2 border-teal-600 border-t-transparent" />
          <p className="mt-3 text-sm text-ink-muted font-medium">Fetching PNR reservation details…</p>
        </div>
      )}

      {/* API Error State */}
      {apiError && !loading && (
        <div
          role="alert"
          className="rounded-2xl border border-rose-200 bg-rose-50/70 p-5 text-sm text-rose-800 space-y-3 animate-in fade-in"
        >
          <div className="flex items-start gap-3">
            <AlertCircle size={20} className="text-rose-600 shrink-0 mt-0.5" />
            <div className="space-y-1 flex-1">
              <h3 className="font-bold text-rose-900">
                {apiError.status === 404
                  ? 'PNR Not Found'
                  : apiError.status === 401
                  ? 'Authentication Required'
                  : apiError.status === 403
                  ? 'Access Restricted'
                  : 'Unable to Load PNR'}
              </h3>
              <p className="text-xs text-rose-700 leading-relaxed">{apiError.message}</p>
            </div>
          </div>

          <div className="flex items-center gap-2 pt-2 border-t border-rose-200/60">
            {apiError.status === 401 ? (
              <Link to={ROUTES.LOGIN}>
                <Button variant="primary" size="sm">
                  Sign In to View
                </Button>
              </Link>
            ) : (
              <Button
                variant="secondary"
                size="sm"
                onClick={() => void handleSearch()}
                disabled={loading}
              >
                <RefreshCw size={13} />
                Try Again
              </Button>
            )}
            <Button variant="ghost" size="sm" onClick={handleReset}>
              Search Another PNR
            </Button>
          </div>
        </div>
      )}

      {/* Booking Details Result Card */}
      {booking && !loading && (
        <Card className="border border-surface-border shadow-card bg-surface-card overflow-hidden animate-in fade-in">
          <CardContent className="p-5 sm:p-6 space-y-5">
            {/* Top Bar: PNR & Statuses */}
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-surface-border/60 pb-4">
              <div className="flex flex-wrap items-center gap-3">
                <span className="font-mono text-sm font-bold text-navy-700 bg-navy-50 border border-navy-200 px-3 py-1 rounded-md">
                  PNR: {booking.pnr || booking.bookingRef}
                </span>
                <span className="font-bold text-ink-primary text-base">
                  {booking.trainNumber} — {booking.trainName}
                </span>
              </div>

              <div className="flex items-center gap-2">
                <Badge
                  variant={
                    booking.bookingStatus === 'CONFIRMED'
                      ? 'teal'
                      : booking.bookingStatus === 'PENDING_PAYMENT'
                      ? 'navy'
                      : booking.bookingStatus === 'CANCELLED'
                      ? 'danger'
                      : 'neutral'
                  }
                  size="sm"
                >
                  {booking.bookingStatus}
                </Badge>
                <span className="text-2xs font-mono font-medium uppercase px-2 py-0.5 rounded bg-slate-100 text-slate-600">
                  {booking.paymentStatus}
                </span>
              </div>
            </div>

            {/* Route & Times Grid */}
            <div className="grid grid-cols-1 sm:grid-cols-12 gap-4 items-center">
              {/* Origin */}
              <div className="sm:col-span-4">
                <span className="text-2xs font-semibold uppercase text-ink-muted">Boarding Station</span>
                <div className="flex items-baseline gap-2 mt-0.5">
                  <span className="font-mono text-lg font-bold text-navy-700">
                    {booking.fromStationCode}
                  </span>
                  <span className="text-xs text-ink-secondary truncate">
                    {booking.fromStationName}
                  </span>
                </div>
                <div className="flex items-center gap-1 text-xs text-ink-muted mt-1">
                  <Clock size={13} className="text-teal-600" />
                  <span>Dep: {booking.departureTime}</span>
                </div>
              </div>

              {/* Journey Date & Direction */}
              <div className="sm:col-span-4 flex flex-col items-center justify-center">
                <div className="flex items-center gap-1.5 text-xs font-medium text-navy-700 bg-surface-subtle/50 px-2.5 py-1 rounded-full">
                  <Calendar size={13} className="text-teal-600" />
                  <span>{booking.journeyDate}</span>
                </div>
                <div className="relative flex w-full max-w-[120px] items-center my-2">
                  <div className="h-2 w-2 rounded-full border border-teal-600 bg-teal-600" />
                  <div className="h-0.5 flex-1 bg-surface-subtle mx-1" />
                  <ArrowRight size={13} className="text-teal-600" />
                  <div className="h-2 w-2 rounded-full border border-navy-700 bg-navy-700" />
                </div>
              </div>

              {/* Destination */}
              <div className="sm:col-span-4">
                <span className="text-2xs font-semibold uppercase text-ink-muted">Alighting Station</span>
                <div className="flex items-baseline gap-2 mt-0.5">
                  <span className="font-mono text-lg font-bold text-navy-700">
                    {booking.toStationCode}
                  </span>
                  <span className="text-xs text-ink-secondary truncate">
                    {booking.toStationName}
                  </span>
                </div>
                <div className="flex items-center gap-1 text-xs text-ink-muted mt-1">
                  <Clock size={13} className="text-teal-600" />
                  <span>Arr: {booking.arrivalTime}</span>
                </div>
              </div>
            </div>

            {/* Reservation & Passenger Details */}
            <div className="rounded-xl border border-surface-border bg-surface-subtle/30 p-4">
              <h4 className="text-xs font-semibold uppercase tracking-wider text-ink-secondary mb-3">
                Berth & Passenger Allocation
              </h4>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 text-xs">
                <div>
                  <span className="text-ink-muted">Coach / Berth</span>
                  <p className="font-mono font-bold text-navy-700 text-sm mt-0.5">
                    {booking.coachNumber} / #{booking.seatNumber}
                  </p>
                </div>
                <div>
                  <span className="text-ink-muted">Berth Type</span>
                  <p className="font-semibold text-ink-primary mt-0.5">
                    {booking.seatType || 'General'} ({booking.coachType})
                  </p>
                </div>
                <div>
                  <span className="text-ink-muted">Booked By</span>
                  <p className="font-semibold text-ink-primary flex items-center gap-1 mt-0.5">
                    <UserIcon size={13} className="text-teal-600" />
                    {booking.username}
                  </p>
                </div>
                <div>
                  <span className="text-ink-muted">Fare Amount</span>
                  <p className="font-mono font-bold text-teal-700 text-sm mt-0.5">
                    {booking.amount != null ? `₹${booking.amount.toFixed(2)}` : 'Unavailable'}
                  </p>
                </div>
              </div>
            </div>

            {/* Bottom Meta Bar */}
            <div className="flex flex-wrap items-center justify-between gap-3 text-2xs text-ink-muted border-t border-surface-border/40 pt-3">
              <div className="flex items-center gap-2">
                <span>Ref:</span>
                <span className="font-mono font-medium text-ink-secondary">{booking.bookingRef}</span>
              </div>

              {booking.bookingStatus === 'CONFIRMED' && (
                <div className="flex items-center gap-1 text-teal-700 font-medium">
                  <CheckCircle2 size={13} />
                  Confirmed Reservation. Valid photo ID required during travel.
                </div>
              )}

              {booking.bookingStatus === 'CANCELLED' && (
                <div className="flex items-center gap-1 text-rose-700 font-medium">
                  <AlertCircle size={13} />
                  This booking has been cancelled and seat released.
                </div>
              )}

              {booking.bookingStatus === 'PENDING_PAYMENT' && (
                <div className="flex items-center gap-2">
                  <span className="text-amber-700 font-medium flex items-center gap-1">
                    <CreditCard size={13} />
                    Payment pending for this reservation.
                  </span>
                </div>
              )}
            </div>
          </CardContent>
        </Card>
      )}

      {/* Default Guide State (When No Search Done Yet) */}
      {!booking && !loading && !apiError && (
        <div className="rounded-2xl border border-surface-border bg-surface-subtle/20 p-6 text-center">
          <Ticket size={28} className="mx-auto text-teal-600/70 mb-2" />
          <h3 className="text-sm font-bold text-ink-primary">Where can I find my PNR?</h3>
          <p className="mt-1 text-xs text-ink-muted max-w-md mx-auto leading-relaxed">
            Your 10-digit Passenger Name Record (PNR) is printed on the top-left corner of your reservation slip or sent via SMS/confirmation email upon seat booking.
          </p>
        </div>
      )}
    </div>
  );
}
