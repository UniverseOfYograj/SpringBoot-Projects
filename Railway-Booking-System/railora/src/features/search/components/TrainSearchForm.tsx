import React, { useState, useEffect } from 'react';
import { ArrowLeftRight, Search, Calendar, MapPin, Info } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { POPULAR_STATIONS, POPULAR_ROUTES, validateStationCode, validateTravelDate } from '../utils';

export interface TrainSearchFormProps {
  initialFrom?: string;
  initialTo?: string;
  initialDate?: string;
  isLoading?: boolean;
  onSearch: (params: { from: string; to: string; date: string }) => void;
}

export function TrainSearchForm({
  initialFrom = '',
  initialTo = '',
  initialDate = '',
  isLoading = false,
  onSearch,
}: TrainSearchFormProps) {
  // Default to today if no date provided
  const todayStr = new Date().toISOString().split('T')[0];

  const [from, setFrom] = useState(initialFrom.toUpperCase());
  const [to, setTo] = useState(initialTo.toUpperCase());
  const [date, setDate] = useState(initialDate || todayStr);

  const [fromError, setFromError] = useState<string | null>(null);
  const [toError, setToError] = useState<string | null>(null);
  const [dateError, setDateError] = useState<string | null>(null);
  const [generalError, setGeneralError] = useState<string | null>(null);

  // Sync state if props change (e.g., URL parameters change)
  useEffect(() => {
    setFrom(initialFrom.toUpperCase());
  }, [initialFrom]);

  useEffect(() => {
    setTo(initialTo.toUpperCase());
  }, [initialTo]);

  useEffect(() => {
    if (initialDate) {
      setDate(initialDate);
    }
  }, [initialDate]);

  const handleSwap = () => {
    const prevFrom = from;
    const prevTo = to;
    setFrom(prevTo);
    setTo(prevFrom);
    setFromError(null);
    setToError(null);
    setGeneralError(null);
  };

  const handleSelectRoute = (routeFrom: string, routeTo: string) => {
    setFrom(routeFrom);
    setTo(routeTo);
    setFromError(null);
    setToError(null);
    setGeneralError(null);

    const dErr = date ? validateTravelDate(date) : null;
    setDateError(dErr);
    if (dErr) return;

    onSearch({ from: routeFrom, to: routeTo, date });
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();

    const normalizedFrom = from.trim().toUpperCase();
    const normalizedTo = to.trim().toUpperCase();

    const fErr = validateStationCode(normalizedFrom);
    const tErr = validateStationCode(normalizedTo);
    const dErr = date ? validateTravelDate(date) : null;

    setFromError(fErr);
    setToError(tErr);
    setDateError(dErr);

    if (fErr || tErr || dErr) {
      setGeneralError(null);
      return;
    }

    if (normalizedFrom === normalizedTo) {
      setGeneralError('Origin and destination stations cannot be the same.');
      return;
    }

    setGeneralError(null);
    onSearch({ from: normalizedFrom, to: normalizedTo, date });
  };

  return (
    <div className="w-full rounded-2xl bg-surface-card p-6 shadow-sm border border-surface-border">
      <form onSubmit={handleSubmit} noValidate>
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-12 lg:items-end">

          {/* Origin Station */}
          <div className="lg:col-span-4">
            <Input
              id="search-from-station"
              label="Origin Station"
              placeholder="e.g. HWH"
              value={from}
              maxLength={30}
              onChange={(e) => {
                setFrom(e.target.value.toUpperCase());
                if (fromError) setFromError(null);
                if (generalError) setGeneralError(null);
              }}
              error={fromError || undefined}
              helperText="Enter 2–30 character station code"
              leftIcon={<MapPin size={16} />}
              required
            />
          </div>

          {/* Swap Button */}
          <div className="flex items-center justify-center lg:col-span-1 lg:pb-5">
            <button
              type="button"
              onClick={handleSwap}
              aria-label="Swap origin and destination"
              title="Swap stations"
              className="group flex h-10 w-10 items-center justify-center rounded-full
                         border border-surface-border bg-surface-muted text-ink-secondary
                         hover:bg-slate-200 hover:text-ink-primary hover:border-surface-subtle
                         focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500
                         active:scale-95 transition-interact cursor-pointer"
            >
              <ArrowLeftRight
                size={16}
                className="transition-transform duration-200 group-hover:rotate-180"
                aria-hidden="true"
              />
            </button>
          </div>

          {/* Destination Station */}
          <div className="lg:col-span-4">
            <Input
              id="search-to-station"
              label="Destination Station"
              placeholder="e.g. NDLS"
              value={to}
              maxLength={30}
              onChange={(e) => {
                setTo(e.target.value.toUpperCase());
                if (toError) setToError(null);
                if (generalError) setGeneralError(null);
              }}
              error={toError || undefined}
              helperText="Enter 2–30 character station code"
              leftIcon={<MapPin size={16} />}
              required
            />
          </div>

          {/* Travel Date */}
          <div className="lg:col-span-3">
            <Input
              id="search-travel-date"
              label="Travel Date"
              type="date"
              min={todayStr}
              value={date}
              onChange={(e) => {
                setDate(e.target.value);
                if (dateError) setDateError(null);
              }}
              error={dateError || undefined}
              leftIcon={<Calendar size={16} />}
              helperText={dateError ? undefined : "Scheduled timetable context"}
            />
          </div>
        </div>

        {/* Same Station / General Error Alert */}
        {generalError && (
          <div
            role="alert"
            className="mt-4 flex items-center gap-2 rounded-lg border border-status-danger-border bg-status-danger-bg p-3 text-xs font-medium text-status-danger-text"
          >
            <Info size={16} className="shrink-0" aria-hidden="true" />
            <span>{generalError}</span>
          </div>
        )}

        {/* Form Actions & Search Note */}
        <div className="mt-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between border-t border-surface-border pt-4">
          <div className="flex items-center gap-2 text-xs text-ink-muted">
            <Info size={14} className="text-teal-600 shrink-0" aria-hidden="true" />
            <span>Timetable search returns scheduled direct routes from official backend data.</span>
          </div>

          <Button
            type="submit"
            variant="accent"
            size="md"
            isLoading={isLoading}
            leftIcon={<Search size={16} />}
            className="w-full sm:w-auto px-6 font-medium shadow-sm"
          >
            Search Trains
          </Button>
        </div>
      </form>

      {/* Quick Route Suggestions */}
      <div className="mt-5 border-t border-surface-border/60 pt-4">
        {/* Seeded Production Demo Shortcut */}
        <div className="mb-4 rounded-xl border border-teal-200 bg-teal-50/80 p-3.5 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 shadow-2xs">
          <div className="flex flex-col sm:flex-row sm:items-center gap-2">
            <span className="inline-flex items-center justify-center rounded-md bg-teal-700 px-2.5 py-1 font-mono text-2xs font-bold text-white uppercase tracking-wider shrink-0 w-fit">
              Live Demo Run
            </span>
            <div>
              <span className="text-xs font-bold text-navy-800">
                Train 12301 | HWH → NDLS | 1 December 2026
              </span>
              <p className="text-2xs text-ink-muted mt-0.5">
                Pre-configured test inventory with 16 available berths across 2A, 3A, and Sleeper coaches.
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={() => {
              setFrom('HWH');
              setTo('NDLS');
              setDate('2026-12-01');
              setFromError(null);
              setToError(null);
              setDateError(null);
              setGeneralError(null);
              onSearch({ from: 'HWH', to: 'NDLS', date: '2026-12-01' });
            }}
            className="inline-flex items-center justify-center gap-2 rounded-lg bg-teal-600 px-3.5 py-2 text-xs font-bold text-white shadow-xs hover:bg-teal-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500 transition-interact cursor-pointer shrink-0"
            aria-label="Try Demo Booking — Train 12301 | HWH → NDLS | 1 Dec 2026"
          >
            <span>Try Demo Booking — Train 12301 | HWH → NDLS | 1 Dec 2026</span>
            <Search size={14} aria-hidden="true" />
          </button>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs font-semibold uppercase tracking-wider text-ink-muted mr-1">
            Popular Routes:
          </span>
          {POPULAR_ROUTES.map((route) => (
            <button
              key={`${route.from}-${route.to}`}
              type="button"
              onClick={() => handleSelectRoute(route.from, route.to)}
              className="inline-flex items-center rounded-full border border-surface-border bg-surface-muted/60
                         px-3 py-1 text-xs font-medium text-ink-secondary
                         hover:border-teal-500 hover:bg-teal-50 hover:text-teal-700
                         focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500
                         transition-interact cursor-pointer"
            >
              {route.label}
            </button>
          ))}
        </div>

        <div className="mt-2.5 flex flex-wrap items-center gap-1.5">
          <span className="text-xs font-medium text-ink-muted mr-1">
            Station Hubs:
          </span>
          {POPULAR_STATIONS.slice(0, 8).map((stn) => (
            <button
              key={stn.code}
              type="button"
              title={`${stn.name} (${stn.city})`}
              onClick={() => {
                if (!from) {
                  setFrom(stn.code);
                  setFromError(null);
                } else if (!to && from !== stn.code) {
                  setTo(stn.code);
                  setToError(null);
                } else {
                  setFrom(stn.code);
                  setFromError(null);
                }
              }}
              className="inline-flex items-center rounded-md border border-surface-border/80 bg-surface-base
                         px-2 py-0.5 text-2xs font-mono font-medium text-ink-secondary
                         hover:border-navy-700 hover:text-navy-800
                         focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-teal-500
                         transition-interact cursor-pointer"
            >
              {stn.code}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
