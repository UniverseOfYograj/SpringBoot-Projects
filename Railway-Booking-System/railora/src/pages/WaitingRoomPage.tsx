import { useState, useEffect } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { Train, ArrowRight, ShieldCheck, Zap } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { WaitingRoomCard } from '@/features/waiting-room/components/WaitingRoomCard';
import { useWaitingRoom } from '@/features/waiting-room/useWaitingRoom';
import { SeatSelectionModal } from '@/features/booking/components/SeatSelectionModal';
import { bookingApi } from '@/features/booking/api';
import type { TrainSearchResponseDTO } from '@/features/search/types';
import type { QuotaType } from '@/features/waiting-room/types';
import { ROUTES } from '@/lib/constants';

export default function WaitingRoomPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  const journeyIdParam = searchParams.get('journeyId');
  const directJourneyId = journeyIdParam ? parseInt(journeyIdParam, 10) : undefined;
  const trainIdParam = searchParams.get('trainId');
  const trainId = trainIdParam ? parseInt(trainIdParam, 10) : undefined;
  const trainNumber = searchParams.get('trainNumber') ?? 'TATKAL-EXP';
  const trainName = searchParams.get('trainName') ?? 'Tatkal Express';
  const fromStationCode = searchParams.get('fromStationCode') ?? '';
  const toStationCode = searchParams.get('toStationCode') ?? '';
  const fromStationName = searchParams.get('fromStationName') ?? fromStationCode;
  const toStationName = searchParams.get('toStationName') ?? toStationCode;
  const dateParam = searchParams.get('date') ?? '';
  const quotaParam = (searchParams.get('quota')?.toUpperCase() as QuotaType) || 'TATKAL';

  const [resolvedJourneyId, setResolvedJourneyId] = useState<number | undefined>(directJourneyId);
  const [resolutionError, setResolutionError] = useState<string | null>(null);
  const [isModalOpen, setIsModalOpen] = useState(false);

  const { state, retry, leave, join } = useWaitingRoom({
    trainId,
    journeyDate: dateParam,
    journeyId: resolvedJourneyId,
    quota: quotaParam,
    autoJoin: Boolean(directJourneyId),
  });

  // Resolve journeyId from trainId, directional station stops, and travel date if not supplied directly
  useEffect(() => {
    let active = true;

    async function resolveScheduledJourney() {
      if (directJourneyId || !trainId || !dateParam) return;

      try {
        const journeys = await bookingApi.getJourneys({ trainId, date: dateParam });
        if (!active) return;

        if (!journeys || journeys.length === 0) {
          setResolutionError(`No scheduled journey found for ${trainNumber} on ${dateParam}.`);
          return;
        }

        let matched: typeof journeys[0] | null = null;
        if (fromStationCode && toStationCode) {
          for (const candidate of journeys) {
            try {
              const stops = await bookingApi.getJourneyStops(candidate.journeyId);
              const bStop = stops.find(
                (s) => s.stationCode.toUpperCase() === fromStationCode.toUpperCase(),
              );
              const aStop = stops.find(
                (s) => s.stationCode.toUpperCase() === toStationCode.toUpperCase(),
              );
              if (bStop && aStop && bStop.stopSequence < aStop.stopSequence) {
                matched = candidate;
                break;
              }
            } catch {
              // Ignore individual stop resolution error; continue evaluating other candidates
            }
          }

          if (!matched) {
            setResolutionError(
              `No directional journey run matches ${fromStationCode} → ${toStationCode} on ${dateParam}.`,
            );
            return;
          }
        } else {
          matched = journeys[0];
        }

        if (active && matched.journeyId) {
          setResolvedJourneyId(matched.journeyId);
          setResolutionError(null);
          void join({
            trainId,
            journeyDate: dateParam,
            journeyId: matched.journeyId,
            quota: quotaParam,
          });
        }
      } catch {
        // Fall back gracefully in offline/mock tests where getJourneys is not stubbed
        if (active) {
          void join({ trainId, journeyDate: dateParam, quota: quotaParam });
        }
      }
    }

    void resolveScheduledJourney();

    return () => {
      active = false;
    };
  }, [
    directJourneyId,
    trainId,
    trainNumber,
    dateParam,
    fromStationCode,
    toStationCode,
    quotaParam,
    join,
  ]);

  const modalTrain: TrainSearchResponseDTO = {
    trainId: trainId ?? 1,
    trainNumber,
    trainName,
    fromStationCode: fromStationCode || 'MAS',
    fromStationName: fromStationName || 'Chennai Central',
    fromArrivalTime: null,
    fromDepartureTime: '10:00:00',
    fromDayOffset: 0,
    toStationCode: toStationCode || 'SBC',
    toStationName: toStationName || 'Bangalore City',
    toArrivalTime: '16:00:00',
    toDepartureTime: null,
    toDayOffset: 0,
  };

  const hasTarget = Boolean(directJourneyId || (trainId && dateParam));

  return (
    <div className="min-h-screen bg-surface-base py-8 sm:py-12">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">

        {/* ── Page Header ── */}
        <div className="mb-8 text-center sm:text-left">
          <div className="inline-flex items-center gap-1.5 rounded-full border border-amber-200 bg-amber-50 px-3 py-1 text-2xs font-bold text-amber-800 uppercase tracking-wider mb-2">
            <Zap size={12} className="text-amber-600" />
            High-Concurrency Tatkal Protection
          </div>
          <h1 className="text-2xl sm:text-3xl font-extrabold tracking-tight text-ink-primary">
            Virtual Waiting Room
          </h1>
          <p className="mt-1 max-w-2xl text-xs sm:text-sm text-ink-secondary">
            Protecting travelers from system overload during peak Tatkal booking windows.
            Fair, timestamped queueing backed by Redis concurrency control.
          </p>
        </div>

        {/* ── Main Waiting Room Content ── */}
        {hasTarget ? (
          <div>
            <WaitingRoomCard
              state={resolutionError ? { ...state, status: 'ERROR', error: resolutionError } : state}
              train={{
                trainNumber,
                trainName,
                fromStationCode,
                toStationCode,
                fromStationName,
                toStationName,
              }}
              travelDate={dateParam}
              quota={quotaParam}
              onProceedToBooking={() => setIsModalOpen(true)}
              onLeave={leave}
              onRetry={retry}
              onRejoin={() => {
                setResolutionError(null);
                void join({
                  trainId,
                  journeyDate: dateParam,
                  journeyId: resolvedJourneyId,
                  quota: quotaParam,
                });
              }}
              onSignIn={() => navigate(ROUTES.LOGIN)}
            />
          </div>
        ) : (
          /* Empty / Unspecified State */
          <div className="mx-auto max-w-xl rounded-2xl border border-surface-border bg-white p-8 text-center shadow-card">
            <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-teal-50 text-teal-600 mb-4">
              <Train size={30} />
            </div>
            <h2 className="text-lg font-bold text-ink-primary">
              No Tatkal Train Selected
            </h2>
            <p className="mt-2 text-xs sm:text-sm text-ink-muted leading-relaxed">
              To enter the Virtual Waiting Room for Tatkal ticket bookings, please search for your train
              and click the &ldquo;Tatkal Queue&rdquo; button.
            </p>
            <div className="mt-6 flex justify-center">
              <Button
                variant="primary"
                size="md"
                onClick={() => navigate(ROUTES.SEARCH)}
              >
                <span>Search Trains for Tatkal</span>
                <ArrowRight size={15} />
              </Button>
            </div>
          </div>
        )}

        {/* ── Information / Architecture Guarantee Footer ── */}
        <div className="mt-12 mx-auto max-w-2xl rounded-xl border border-surface-border bg-white p-4 sm:p-5 text-xs text-ink-secondary space-y-2">
          <div className="flex items-center gap-2 font-bold text-ink-primary">
            <ShieldCheck size={16} className="text-teal-600" />
            <span>How the Railora Tatkal Waiting Room Operates</span>
          </div>
          <p className="text-ink-muted leading-relaxed">
            During peak Tatkal opening hours (10:00 AM &amp; 11:00 AM), thousands of concurrent requests attempt to reserve limited seats.
            Railora buffers traffic into a FIFO queue. Once admitted, you receive a temporary booking token to proceed to seat selection.
          </p>
        </div>
      </div>

      {/* ── Booking Modal when Admitted ── */}
      {isModalOpen && (
        <SeatSelectionModal
          isOpen={isModalOpen}
          onClose={() => setIsModalOpen(false)}
          train={modalTrain}
          travelDate={dateParam || new Date().toISOString().split('T')[0]}
          journeyId={resolvedJourneyId}
          admissionToken={state.admissionToken ?? state.queueToken ?? undefined}
          quota={quotaParam}
        />
      )}
    </div>
  );
}
