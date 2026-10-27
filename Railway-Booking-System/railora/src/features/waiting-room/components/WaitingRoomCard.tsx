import {
  Train,
  Clock,
  Users,
  CheckCircle2,
  AlertCircle,
  AlertTriangle,
  LogOut,
  RefreshCw,
  ArrowRight,
  ShieldCheck,
  Zap,
  Loader2,
} from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Badge';
import { formatEstimatedWait } from '../useWaitingRoom';
import type { WaitingRoomState, QuotaType } from '../types';

export interface WaitingRoomCardProps {
  state: WaitingRoomState;
  train?: {
    trainNumber: string;
    trainName: string;
    fromStationCode?: string;
    toStationCode?: string;
    fromStationName?: string;
    toStationName?: string;
  };
  travelDate?: string;
  quota?: QuotaType;
  onProceedToBooking?: (admissionToken: string) => void;
  onLeave?: () => void;
  onRetry?: () => void;
  onRejoin?: () => void;
  onSignIn?: () => void;
}

export function WaitingRoomCard({
  state,
  train,
  travelDate,
  quota = 'TATKAL',
  onProceedToBooking,
  onLeave,
  onRetry,
  onRejoin,
  onSignIn,
}: WaitingRoomCardProps) {
  const { hasEstimate, displayText: waitTimeDisplay } = formatEstimatedWait(
    state.estimatedWaitSeconds,
  );

  // Compute honest queue progress if totalInQueue is known
  const position = state.position ?? 1;
  const total = Math.max(position, state.totalInQueue ?? position);
  const progressPercent = total > 0 ? Math.min(100, Math.max(5, Math.round(((total - position + 1) / total) * 100))) : 50;

  return (
    <article
      className="mx-auto max-w-2xl overflow-hidden rounded-2xl border border-surface-border bg-white shadow-card transition-interact"
      aria-label="Tatkal Virtual Waiting Room"
    >
      {/* ── Card Header ── */}
      <header className="border-b border-surface-border bg-surface-subtle/30 px-6 py-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-navy-700 text-white shadow-sm">
              <Train size={20} />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="font-mono text-sm font-bold text-navy-700">
                  {train?.trainNumber ?? 'TATKAL SERVICE'}
                </span>
                <span className="text-xs text-ink-muted">•</span>
                <span className="text-sm font-bold text-ink-primary">
                  {train?.trainName ?? 'Express'}
                </span>
              </div>
              {(train?.fromStationCode || travelDate) && (
                <p className="mt-0.5 text-xs text-ink-muted">
                  {train?.fromStationCode && train?.toStationCode && (
                    <span>
                      {train.fromStationCode} → {train.toStationCode}
                      {travelDate && ' • '}
                    </span>
                  )}
                  {travelDate && <span>Travel Date: <strong>{travelDate}</strong></span>}
                </p>
              )}
            </div>
          </div>

          <div className="flex items-center gap-2">
            <Badge variant="warning" size="sm" dot>
              <Zap size={11} className="inline mr-0.5" />
              {quota} Quota
            </Badge>
          </div>
        </div>
      </header>

      {/* ── Card Body / State Views ── */}
      <div className="p-6 sm:p-8">

        {/* ── Reconnecting Banner ── */}
        {state.isReconnecting && state.status !== 'ERROR' && (
          <div
            role="status"
            aria-live="polite"
            className="mb-6 flex items-center justify-between rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-900"
          >
            <div className="flex items-center gap-2">
              <Loader2 size={16} className="animate-spin text-amber-600" />
              <span>
                Reconnecting to waiting room (attempt {state.reconnectAttempts} of 5)…
              </span>
            </div>
            {onRetry && (
              <Button
                variant="ghost"
                size="sm"
                onClick={onRetry}
                className="h-7 text-xs text-amber-900 hover:bg-amber-100"
              >
                Retry Now
              </Button>
            )}
          </div>
        )}

        {/* ── STATE 1: CONNECTING ── */}
        {state.status === 'CONNECTING' && (
          <section
            role="status"
            aria-live="polite"
            className="py-12 text-center"
            aria-busy="true"
          >
            <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-teal-50 text-teal-600">
              <Loader2 size={32} className="animate-spin" />
            </div>
            <h3 className="mt-4 text-lg font-bold text-ink-primary">
              Connecting to Tatkal Waiting Room…
            </h3>
            <p className="mx-auto mt-2 max-w-md text-xs text-ink-muted">
              Securing your timestamped place in line. High-concurrency traffic is being prioritized fairly.
            </p>
          </section>
        )}

        {/* ── STATE 2: QUEUED ── */}
        {state.status === 'QUEUED' && (
          <section role="status" aria-live="polite">
            {/* Live Queue Indicator */}
            <div className="flex items-center justify-between border-b border-surface-border/60 pb-4">
              <div className="flex items-center gap-2 text-xs text-ink-secondary">
                <span className="relative flex h-2.5 w-2.5">
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-teal-400 opacity-75" />
                  <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-teal-500" />
                </span>
                <span className="font-semibold text-teal-700">Queue Active</span>
                <span className="text-ink-muted">• Auto-updating</span>
              </div>
              <div className="text-2xs text-ink-muted" aria-busy={state.isPolling}>
                {state.isPolling ? 'Checking…' : 'Position live'}
              </div>
            </div>

            {/* Position Display */}
            <div className="my-6 rounded-2xl border border-surface-border bg-slate-50/70 p-6 text-center">
              <span className="text-xs font-semibold uppercase tracking-wider text-ink-muted">
                Your Position in Queue
              </span>
              <div className="my-2 flex items-baseline justify-center gap-1">
                <span className="font-mono text-5xl sm:text-6xl font-black tracking-tight text-navy-700">
                  #{state.position}
                </span>
              </div>
              {state.totalInQueue !== null && state.totalInQueue > 0 && (
                <p className="flex items-center justify-center gap-1.5 text-xs text-ink-muted">
                  <Users size={14} className="text-ink-secondary" />
                  <span>of ~{state.totalInQueue} travelers in line</span>
                </p>
              )}
            </div>

            {/* Accessible Progress Indicator */}
            <div className="my-6 space-y-2">
              <div className="flex justify-between text-2xs font-semibold text-ink-secondary">
                <span>Advancing toward admission</span>
                <span>{progressPercent}%</span>
              </div>
              <div
                role="progressbar"
                aria-valuenow={progressPercent}
                aria-valuemin={0}
                aria-valuemax={100}
                aria-label="Queue advancement progress"
                className="h-2.5 w-full overflow-hidden rounded-full bg-surface-muted"
              >
                <div
                  className="h-full rounded-full bg-teal-500 transition-all duration-500 ease-out"
                  style={{ width: `${progressPercent}%` }}
                />
              </div>
            </div>

            {/* Honest Wait-Time Estimation Box */}
            <div className="my-6 rounded-xl border border-teal-100 bg-teal-50/50 p-4">
              <div className="flex items-start gap-3">
                <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-teal-100 text-teal-700">
                  <Clock size={16} />
                </div>
                <div>
                  <h4 className="text-xs font-bold text-teal-900">
                    {hasEstimate ? `Estimated Wait: ${waitTimeDisplay}` : 'Estimated Wait Time'}
                  </h4>
                  <p className="mt-1 text-xs text-teal-800 leading-relaxed">
                    {hasEstimate
                      ? 'Calculated dynamically from real-time checkout throughput. Your place will automatically advance.'
                      : 'Wait time is being estimated based on queue velocity and checkout speeds. We do not display artificial countdowns.'}
                  </p>
                </div>
              </div>
            </div>

            {/* Queue Instructions Notice */}
            <div className="rounded-xl border border-surface-border bg-surface-base p-4 text-xs text-ink-secondary">
              <div className="flex items-center gap-1.5 font-semibold text-ink-primary mb-1">
                <ShieldCheck size={14} className="text-teal-600" />
                Fair Allocation & High-Demand Protection
              </div>
              <p className="leading-relaxed text-ink-muted">
                Please keep this tab open. When your turn arrives, you will be automatically admitted to select and hold your seats.
              </p>
            </div>

            {/* Action Bar (Voluntary Leave) */}
            <div className="mt-6 flex items-center justify-between border-t border-surface-border pt-4">
              <span className="text-xs text-ink-muted">
                Need to cancel or change plans?
              </span>
              {onLeave && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={onLeave}
                  className="text-ink-secondary hover:text-status-danger-text hover:bg-status-danger-bg"
                >
                  <LogOut size={14} />
                  Leave Queue
                </Button>
              )}
            </div>
          </section>
        )}

        {/* ── STATE 3: ADMITTED ── */}
        {state.status === 'ADMITTED' && (
          <section role="status" aria-live="assertive" className="text-center py-4">
            <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-2xl bg-teal-50 text-teal-600 shadow-inner">
              <CheckCircle2 size={36} />
            </div>

            <Badge variant="success" size="md" className="mt-4">
              Admission Granted
            </Badge>

            <h3 className="mt-3 text-xl sm:text-2xl font-bold text-ink-primary">
              You are Admitted to Book!
            </h3>

            <p className="mx-auto mt-2 max-w-md text-xs sm:text-sm text-ink-secondary">
              Your turn has arrived. You now have a priority window to proceed directly to seat selection and hold.
            </p>

            {/* Admission Token Box & Disclaimer */}
            <div className="my-6 mx-auto max-w-md rounded-xl border border-teal-200 bg-teal-50/60 p-4 text-left text-xs space-y-2">
              <div className="flex items-center justify-between border-b border-teal-200/60 pb-2">
                <span className="text-teal-800 font-medium">Admission Token</span>
                <span className="font-mono font-bold text-navy-700 truncate max-w-[180px]">
                  {state.admissionToken ?? state.queueToken}
                </span>
              </div>
              <p className="text-teal-900 text-2xs leading-relaxed">
                <strong>Important:</strong> Admission grants you entry to the booking flow and validates your Tatkal window.
                Seat reservations are only confirmed once a seat is held and payment is completed.
              </p>
            </div>

            {/* Proceed CTA */}
            <div className="flex flex-col sm:flex-row items-center justify-center gap-3 pt-2">
              <Button
                variant="accent"
                size="lg"
                onClick={() => onProceedToBooking?.(state.admissionToken ?? state.queueToken ?? '')}
                className="w-full sm:w-auto"
              >
                <span>Proceed to Seat Selection</span>
                <ArrowRight size={16} />
              </Button>
            </div>
          </section>
        )}

        {/* ── STATE 4: EXPIRED ── */}
        {state.status === 'EXPIRED' && (
          <section role="status" aria-live="assertive" className="text-center py-6">
            <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-amber-50 text-amber-600">
              <AlertTriangle size={30} />
            </div>

            <Badge variant="warning" size="md" className="mt-4">
              Session Expired
            </Badge>

            <h3 className="mt-3 text-lg sm:text-xl font-bold text-ink-primary">
              Queue Window Expired
            </h3>

            <p className="mx-auto mt-2 max-w-md text-xs text-ink-muted leading-relaxed">
              Your queue position or admission window has expired due to session timeout. Tatkal tickets have high velocity.
            </p>

            <div className="mt-6 flex justify-center">
              {onRejoin && (
                <Button variant="primary" size="md" onClick={onRejoin}>
                  <RefreshCw size={15} />
                  Rejoin Tatkal Queue
                </Button>
              )}
            </div>
          </section>
        )}

        {/* ── STATE 5: LEFT ── */}
        {state.status === 'LEFT' && (
          <section role="status" aria-live="polite" className="text-center py-6">
            <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-slate-100 text-ink-muted">
              <LogOut size={28} />
            </div>

            <h3 className="mt-3 text-lg font-bold text-ink-primary">
              You Have Left the Queue
            </h3>

            <p className="mx-auto mt-2 max-w-md text-xs text-ink-muted">
              You voluntarily exited the Tatkal waiting room. Your position has been yielded to the next traveler.
            </p>

            <div className="mt-6 flex justify-center">
              {onRejoin && (
                <Button variant="primary" size="md" onClick={onRejoin}>
                  <RefreshCw size={15} />
                  Rejoin Tatkal Queue
                </Button>
              )}
            </div>
          </section>
        )}

        {/* ── STATE 6: ERROR ── */}
        {state.status === 'ERROR' && (
          <section role="alert" className="text-center py-6">
            <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-status-danger-bg text-status-danger-text">
              <AlertCircle size={30} />
            </div>

            <Badge variant="danger" size="md" className="mt-4">
              {state.error && /authentication required|sign in|unauthorized/i.test(state.error)
                ? 'Authentication Required'
                : 'Connection Issue'}
            </Badge>

            <h3 className="mt-3 text-lg font-bold text-ink-primary">
              {state.error && /authentication required|sign in|unauthorized/i.test(state.error)
                ? 'Sign In to Access Tatkal Queue'
                : 'Waiting Room Unavailable'}
            </h3>

            <p className="mx-auto mt-2 max-w-md text-xs text-status-danger-text leading-relaxed">
              {state.error ?? 'A temporary network interruption prevented communication with the queue server.'}
            </p>

            <div className="mt-6 flex justify-center gap-3">
              {state.error && /authentication required|sign in|unauthorized/i.test(state.error) && onSignIn ? (
                <Button variant="primary" size="md" onClick={onSignIn}>
                  <span>Sign In</span>
                  <ArrowRight size={15} />
                </Button>
              ) : (
                onRetry && (
                  <Button variant="primary" size="md" onClick={onRetry}>
                    <RefreshCw size={15} />
                    Retry Connection
                  </Button>
                )
              )}
            </div>
          </section>
        )}

      </div>
    </article>
  );
}
