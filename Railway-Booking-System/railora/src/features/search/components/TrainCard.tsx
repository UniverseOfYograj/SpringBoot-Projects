import { useState } from 'react';
import { ArrowRight, Clock, Train as TrainIcon, Calendar, Zap } from 'lucide-react';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { formatTimeString, calculateDuration } from '../utils';
import type { TrainSearchResponseDTO } from '../types';
import { SeatSelectionModal } from '@/features/booking/components/SeatSelectionModal';

export interface TrainCardProps {
  train: TrainSearchResponseDTO;
  travelDate?: string;
}

export function TrainCard({ train, travelDate }: TrainCardProps) {
  const [isModalOpen, setIsModalOpen] = useState(false);

  const departureTime = train.fromDepartureTime ?? train.fromArrivalTime;
  const arrivalTime = train.toArrivalTime ?? train.toDepartureTime;

  const formattedDepTime = formatTimeString(departureTime);
  const formattedArrTime = formatTimeString(arrivalTime);
  const duration = calculateDuration(
    departureTime,
    arrivalTime,
    train.fromDayOffset,
    train.toDayOffset,
  );

  const dayDiff = Math.max(0, train.toDayOffset - train.fromDayOffset);

  return (
    <>
      <article
        className="group rounded-xl border border-surface-border bg-surface-card p-5 sm:p-6 shadow-xs
                   hover:shadow-card hover:border-surface-subtle transition-interact"
        aria-label={`${train.trainName} (${train.trainNumber}) from ${train.fromStationName} to ${train.toStationName}`}
      >
        {/* ── Card Header: Train Number, Name & Route Badges ── */}
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-surface-border/60 pb-4">
          <div className="flex flex-wrap items-center gap-2.5">
            <span className="inline-flex items-center rounded-md bg-navy-700 px-2.5 py-1 font-mono text-xs font-bold text-white tracking-wider">
              {train.trainNumber}
            </span>
            <h3 className="text-base sm:text-lg font-bold text-ink-primary tracking-tight">
              {train.trainName}
            </h3>
          </div>

          <div className="flex items-center gap-2">
            <Badge variant="teal" size="sm">
              Direct Route
            </Badge>
            <span className="text-2xs font-medium text-ink-muted hidden sm:inline-flex items-center gap-1">
              <TrainIcon size={12} aria-hidden="true" />
              Timetable Service
            </span>
          </div>
        </div>

        {/* ── Journey Route Timeline ── */}
        <div className="mt-5 grid grid-cols-1 gap-6 sm:grid-cols-12 sm:items-center">

          {/* Departure Details */}
          <div className="sm:col-span-4 flex flex-col">
            <div className="flex items-baseline gap-2">
              <time className="text-2xl sm:text-3xl font-extrabold text-ink-primary font-mono tracking-tight">
                {formattedDepTime}
              </time>
              {train.fromDayOffset > 0 && (
                <span className="text-2xs font-semibold text-ink-muted uppercase">
                  Day {train.fromDayOffset + 1}
                </span>
              )}
            </div>

            <div className="mt-1">
              <div className="flex items-center gap-1.5">
                <span className="font-mono text-sm font-bold text-navy-700">
                  {train.fromStationCode}
                </span>
                <span className="text-sm text-ink-primary font-medium truncate">
                  {train.fromStationName}
                </span>
              </div>
            </div>
          </div>

          {/* Journey Duration & Transit Line */}
          <div className="sm:col-span-4 flex flex-col items-center justify-center py-2 sm:py-0">
            <div className="flex items-center gap-1 text-xs font-semibold text-ink-secondary mb-1">
              <Clock size={12} className="text-teal-600" aria-hidden="true" />
              <span>{duration}</span>
            </div>

            {/* Visual line with track dots */}
            <div className="relative flex w-full max-w-[200px] items-center">
              <div className="h-2 w-2 rounded-full border-2 border-teal-500 bg-white shrink-0" aria-hidden="true" />
              <div className="h-0.5 flex-1 bg-surface-subtle relative mx-1" aria-hidden="true">
                <ArrowRight
                  size={14}
                  className="absolute -top-1.5 left-1/2 -translate-x-1/2 text-teal-600"
                  aria-hidden="true"
                />
              </div>
              <div className="h-2 w-2 rounded-full border-2 border-navy-700 bg-navy-700 shrink-0" aria-hidden="true" />
            </div>

            <div className="mt-1 flex items-center gap-1.5">
              {dayDiff > 0 && (
                <span className="rounded bg-amber-50 border border-amber-200 px-1.5 py-0.2 text-2xs font-semibold text-amber-800">
                  +{dayDiff} {dayDiff === 1 ? 'Day' : 'Days'}
                </span>
              )}
              <span className="text-2xs text-ink-muted">Non-stop segment</span>
            </div>
          </div>

          {/* Arrival Details */}
          <div className="sm:col-span-4 flex flex-col sm:items-end">
            <div className="flex items-baseline gap-2 sm:flex-row-reverse">
              <time className="text-2xl sm:text-3xl font-extrabold text-ink-primary font-mono tracking-tight">
                {formattedArrTime}
              </time>
              {train.toDayOffset > 0 && (
                <span className="text-2xs font-semibold text-ink-muted uppercase">
                  Day {train.toDayOffset + 1}
                </span>
              )}
            </div>

            <div className="mt-1 sm:text-right">
              <div className="flex items-center gap-1.5 sm:justify-end">
                <span className="text-sm text-ink-primary font-medium truncate">
                  {train.toStationName}
                </span>
                <span className="font-mono text-sm font-bold text-navy-700">
                  {train.toStationCode}
                </span>
              </div>
            </div>
          </div>
        </div>

        {/* ── Footer / Booking Action ── */}
        <div className="mt-5 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 border-t border-surface-border/50 pt-4 text-xs text-ink-muted">
          <div className="flex items-center gap-2">
            {travelDate && (
              <span className="inline-flex items-center gap-1 text-ink-secondary">
                <Calendar size={13} className="text-teal-600" aria-hidden="true" />
                Target: {travelDate}
              </span>
            )}
            <span className="hidden sm:inline text-surface-subtle">•</span>
            <span>Verified Scheduled Timetable</span>
          </div>

          <div className="flex items-center gap-2">
            <a
              href={`/waiting-room?trainId=${train.trainId}&trainNumber=${encodeURIComponent(train.trainNumber)}&trainName=${encodeURIComponent(train.trainName)}&fromStationCode=${encodeURIComponent(train.fromStationCode)}&toStationCode=${encodeURIComponent(train.toStationCode)}&date=${encodeURIComponent(travelDate ?? '')}&quota=TATKAL`}
              className="inline-flex items-center gap-1.5 rounded-md border border-amber-300 bg-amber-50 px-2.5 py-1 text-xs font-semibold text-amber-900 transition-interact hover:bg-amber-100 hover:border-amber-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-500"
              aria-label={`Enter Tatkal waiting room for ${train.trainNumber}`}
            >
              <Zap size={13} className="text-amber-600" aria-hidden="true" />
              <span>Tatkal Queue</span>
            </a>

            <Button
              type="button"
              variant="accent"
              size="sm"
              onClick={() => setIsModalOpen(true)}
              aria-label={`Select seats for ${train.trainNumber}`}
            >
              <span>Book Seats</span>
              <ArrowRight size={14} aria-hidden="true" />
            </Button>
          </div>
        </div>
      </article>

      {isModalOpen && (
        <SeatSelectionModal
          isOpen={isModalOpen}
          onClose={() => setIsModalOpen(false)}
          train={train}
          travelDate={travelDate}
        />
      )}
    </>
  );
}
