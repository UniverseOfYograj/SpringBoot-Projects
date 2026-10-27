import { AlertTriangle, Train, ArrowRight, RefreshCw, Sparkles, Compass } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { TrainCard } from './TrainCard';
import { TrainCardSkeleton } from './TrainCardSkeleton';
import type { TrainSearchResponseDTO } from '../types';

export interface TrainListProps {
  trains?: TrainSearchResponseDTO[];
  isLoading: boolean;
  isFetching?: boolean;
  isError: boolean;
  error?: Error | null;
  onRetry: () => void;
  hasSearched: boolean;
  fromCode?: string;
  toCode?: string;
  travelDate?: string;
  onSwapAndSearch?: () => void;
  validationError?: string | null;
}

export function TrainList({
  trains,
  isLoading,
  isFetching = false,
  isError,
  error,
  onRetry,
  hasSearched,
  fromCode = '',
  toCode = '',
  travelDate,
  onSwapAndSearch,
  validationError,
}: TrainListProps) {
  // ── 1. Initial / Guidance State (Before search is triggered) ──
  if (!hasSearched) {
    return (
      <div className="rounded-2xl border border-surface-border bg-surface-card p-8 sm:p-12 text-center shadow-xs">
        <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-teal-50 text-teal-600 border border-teal-200 mb-4">
          <Compass size={28} aria-hidden="true" />
        </div>
        <h3 className="text-xl font-bold text-ink-primary tracking-tight">
          Ready to explore train schedules
        </h3>
        <p className="mx-auto mt-2 max-w-md text-sm text-ink-muted leading-relaxed">
          Enter origin and destination station codes above (e.g., <code className="font-mono text-ink-primary bg-surface-muted px-1 py-0.5 rounded">HWH</code> and <code className="font-mono text-ink-primary bg-surface-muted px-1 py-0.5 rounded">NDLS</code>) to view scheduled direct services from the backend railway database.
        </p>

        <div className="mt-8 grid grid-cols-1 gap-4 sm:grid-cols-3 max-w-2xl mx-auto text-left">
          <div className="rounded-lg border border-surface-border bg-surface-base p-4">
            <div className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-teal-700 mb-1">
              <Sparkles size={14} aria-hidden="true" />
              <span>Real Timetable</span>
            </div>
            <p className="text-xs text-ink-secondary">
              Queries live Spring Boot API against indexed database records.
            </p>
          </div>

          <div className="rounded-lg border border-surface-border bg-surface-base p-4">
            <div className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-teal-700 mb-1">
              <Train size={14} aria-hidden="true" />
              <span>Direct Segment</span>
            </div>
            <p className="text-xs text-ink-secondary">
              Calculates exact arrival and departure day offsets and journey duration.
            </p>
          </div>

          <div className="rounded-lg border border-surface-border bg-surface-base p-4">
            <div className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-teal-700 mb-1">
              <Compass size={14} aria-hidden="true" />
              <span>Station Codes</span>
            </div>
            <p className="text-xs text-ink-secondary">
              Supports standard Indian Railways alphanumeric codes (2 to 5 characters).
            </p>
          </div>
        </div>
      </div>
    );
  }

  // ── 2. Validation Error State (Invalid URL params or same-station selection) ──
  if (validationError) {
    return (
      <div
        role="alert"
        aria-live="polite"
        className="rounded-2xl border border-status-danger-border bg-status-danger-bg p-8 sm:p-10 text-center shadow-xs"
      >
        <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-red-100 text-status-danger-text mb-4">
          <AlertTriangle size={24} aria-hidden="true" />
        </div>
        <h3 className="text-lg font-bold text-status-danger-text">
          Invalid Search Parameters
        </h3>
        <p className="mx-auto mt-2 max-w-lg text-sm text-ink-secondary leading-relaxed">
          {validationError}
        </p>
      </div>
    );
  }

  // ── 3. Loading State (Skeletons & ARIA Live) ──
  if (isLoading) {
    return (
      <section
        role="status"
        aria-busy="true"
        aria-live="polite"
        className="space-y-4"
        aria-label="Searching for trains"
      >
        <div className="flex items-center justify-between px-1">
          <p className="text-sm font-medium text-ink-muted animate-pulse">
            Searching scheduled trains between <span className="font-mono font-bold text-ink-primary">{fromCode}</span> and <span className="font-mono font-bold text-ink-primary">{toCode}</span>...
          </p>
        </div>
        <div className="space-y-4">
          <TrainCardSkeleton />
          <TrainCardSkeleton />
          <TrainCardSkeleton />
        </div>
      </section>
    );
  }

  // ── 4. Error State (Backend / Network Failure) ──
  if (isError) {
    const errorMessage =
      (error as any)?.response?.data?.message ||
      error?.message ||
      'Unable to connect to the railway search backend service. Please ensure the backend server is running on port 8080.';

    return (
      <section
        role="alert"
        aria-live="assertive"
        className="rounded-2xl border border-status-danger-border bg-status-danger-bg p-8 sm:p-10 text-center shadow-xs"
      >
        <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-red-100 text-status-danger-text mb-4">
          <AlertTriangle size={24} aria-hidden="true" />
        </div>
        <h3 className="text-lg font-bold text-status-danger-text">
          Train Search Unavailable
        </h3>
        <p className="mx-auto mt-2 max-w-lg text-sm text-ink-secondary leading-relaxed">
          {errorMessage}
        </p>

        <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
          <Button
            variant="danger"
            size="md"
            onClick={onRetry}
            leftIcon={<RefreshCw size={16} />}
          >
            Retry Search
          </Button>
        </div>
      </section>
    );
  }

  // ── 5. Empty State (Zero results found for station pair) ──
  if (!trains || trains.length === 0) {
    return (
      <div className="rounded-2xl border border-surface-border bg-surface-card p-8 sm:p-12 text-center shadow-xs">
        <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-surface-muted text-ink-muted mb-4">
          <Train size={28} aria-hidden="true" />
        </div>
        <h3 className="text-xl font-bold text-ink-primary tracking-tight">
          No direct trains found
        </h3>
        <p className="mx-auto mt-2 max-w-md text-sm text-ink-muted leading-relaxed">
          There are no scheduled direct routes from{' '}
          <span className="font-mono font-bold text-ink-primary">{fromCode}</span> to{' '}
          <span className="font-mono font-bold text-ink-primary">{toCode}</span> in the current timetable database.
        </p>

        <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
          {onSwapAndSearch && (
            <Button
              variant="secondary"
              size="sm"
              onClick={onSwapAndSearch}
              leftIcon={<ArrowRight size={14} />}
            >
              Search reverse route ({toCode} → {fromCode})
            </Button>
          )}
        </div>

        <div className="mt-8 border-t border-surface-border pt-4 text-xs text-ink-muted max-w-md mx-auto">
          Tip: Verify station codes or try searching major railway junctions such as <span className="font-mono font-semibold">NDLS</span> (New Delhi), <span className="font-mono font-semibold">HWH</span> (Howrah), or <span className="font-mono font-semibold">CSMT</span> (Mumbai).
        </div>
      </div>
    );
  }

  // ── 6. Results List (Success State) ──
  return (
    <section aria-label="Train search results" className="space-y-4">
      {/* Results Meta Banner */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2 px-1">
        <p
          className="text-sm font-semibold text-ink-primary"
          aria-label={`Found ${trains.length} direct trains from ${fromCode} to ${toCode}`}
        >
          Found <span className="text-teal-600 font-bold">{trains.length}</span> direct {trains.length === 1 ? 'train' : 'trains'} from{' '}
          <span className="font-mono text-navy-700 font-bold">{fromCode}</span> to{' '}
          <span className="font-mono text-navy-700 font-bold">{toCode}</span>
        </p>

        <div className="flex items-center gap-2">
          {isFetching && (
            <span className="inline-flex items-center gap-1 text-2xs text-teal-700 font-medium animate-pulse">
              <RefreshCw size={11} className="animate-spin" />
              Updating schedules…
            </span>
          )}
          <p className="text-xs text-ink-muted">
            Showing official scheduled timetables
          </p>
        </div>
      </div>

      {/* Train Cards Stack */}
      <div className="space-y-4" role="feed" aria-label="Train routes list">
        {trains.map((train) => (
          <TrainCard
            key={`${train.trainId}-${train.trainNumber}`}
            train={train}
            travelDate={travelDate}
          />
        ))}
      </div>
    </section>
  );
}
