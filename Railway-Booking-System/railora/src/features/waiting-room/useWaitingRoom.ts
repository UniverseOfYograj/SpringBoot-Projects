import { useEffect, useRef, useState, useCallback } from 'react';
import { waitingRoomApi } from './api';
import type {
  QuotaType,
  WaitingRoomState,
} from './types';

export interface UseWaitingRoomOptions {
  trainId?: number;
  journeyDate?: string;
  journeyId?: number;
  quota?: QuotaType;
  autoJoin?: boolean;
  pollingIntervalMs?: number;
  maxReconnectAttempts?: number;
}

const DEFAULT_POLLING_INTERVAL = 3000;
const DEFAULT_MAX_RECONNECT_ATTEMPTS = 5;

/**
 * Custom React hook for managing the Railora Virtual Waiting Room lifecycle.
 *
 * Responsibilities:
 * - Controlled entry into the Tatkal queue.
 * - Periodic status polling when in QUEUED status.
 * - Exponential / linear reconnect behavior on transient network glitches.
 * - Controlled transition to ADMITTED, EXPIRED, LEFT, or ERROR states.
 * - Clean teardown of timers on unmount or queue exit.
 */
export function useWaitingRoom({
  trainId,
  journeyDate,
  journeyId,
  quota = 'TATKAL',
  autoJoin = true,
  pollingIntervalMs = DEFAULT_POLLING_INTERVAL,
  maxReconnectAttempts = DEFAULT_MAX_RECONNECT_ATTEMPTS,
}: UseWaitingRoomOptions) {
  const [state, setState] = useState<WaitingRoomState>({
    status: autoJoin || (trainId && journeyDate) ? 'CONNECTING' : 'LEFT',
    journeyId: journeyId ?? null,
    queueToken: null,
    admissionToken: null,
    position: null,
    totalInQueue: null,
    estimatedWaitSeconds: null,
    expiresAt: null,
    admittedAt: null,
    isPolling: false,
    isReconnecting: false,
    reconnectAttempts: 0,
    error: null,
    lastUpdated: null,
  });

  const pollInFlightRef = useRef(false);
  const joinInFlightRef = useRef(false);
  const unmountedRef = useRef(false);

  // Keep target params in ref for re-joining
  const paramsRef = useRef({ trainId, journeyDate, journeyId, quota });
  useEffect(() => {
    paramsRef.current = { trainId, journeyDate, journeyId, quota };
  }, [trainId, journeyDate, journeyId, quota]);

  /**
   * Enter or re-enter the queue.
   * Includes duplicate join prevention to avoid duplicate in-flight requests.
   */
  const join = useCallback(
    async (overrideParams?: {
      trainId?: number;
      journeyDate?: string;
      journeyId?: number;
      quota?: QuotaType;
    }) => {
      if (joinInFlightRef.current) return;

      const activeJourneyId = overrideParams?.journeyId ?? paramsRef.current.journeyId;
      const activeTrainId = overrideParams?.trainId ?? paramsRef.current.trainId;
      const activeDate = overrideParams?.journeyDate ?? paramsRef.current.journeyDate;
      const activeQuota = overrideParams?.quota ?? paramsRef.current.quota ?? 'TATKAL';

      if (!activeJourneyId && (!activeTrainId || !activeDate)) {
        setState((prev) => ({
          ...prev,
          status: 'ERROR',
          error: 'Train, journey date, or scheduled journey are required to enter the waiting room.',
        }));
        return;
      }

      joinInFlightRef.current = true;
      setState((prev) => ({
        ...prev,
        status: 'CONNECTING',
        error: null,
        isReconnecting: false,
        reconnectAttempts: 0,
      }));

      try {
        const response = await waitingRoomApi.joinQueue({
          journeyId: activeJourneyId,
          trainId: activeTrainId,
          journeyDate: activeDate,
          quota: activeQuota,
        });

        if (unmountedRef.current) return;

        const resolvedJourneyId = response.journeyId ?? activeJourneyId ?? null;

        if (response.status === 'ADMITTED') {
          setState({
            status: 'ADMITTED',
            journeyId: resolvedJourneyId,
            queueToken: response.queueToken,
            admissionToken: response.admissionToken ?? response.admissionPass ?? response.queueToken,
            position: 0,
            totalInQueue: response.totalInQueue ?? 0,
            estimatedWaitSeconds: 0,
            expiresAt: response.expiresAt ?? response.admittedUntil ?? null,
            admittedAt: new Date().toISOString(),
            isPolling: false,
            isReconnecting: false,
            reconnectAttempts: 0,
            error: null,
            lastUpdated: Date.now(),
          });
        } else {
          setState({
            status: 'QUEUED',
            journeyId: resolvedJourneyId,
            queueToken: response.queueToken,
            admissionToken: null,
            position: response.position,
            totalInQueue: response.totalInQueue ?? null,
            estimatedWaitSeconds: response.estimatedWaitSeconds ?? null,
            expiresAt: response.expiresAt ?? response.admittedUntil ?? null,
            admittedAt: null,
            isPolling: false,
            isReconnecting: false,
            reconnectAttempts: 0,
            error: null,
            lastUpdated: Date.now(),
          });
        }
      } catch (err: unknown) {
        if (unmountedRef.current) return;
        const status =
          err && typeof err === 'object' && 'response' in err
            ? (err as { response?: { status?: number } }).response?.status
            : null;
        const msg =
          status === 401
            ? 'Authentication required. Please sign in to join the Tatkal waiting room.'
            : (err && typeof err === 'object' && 'response' in err
                ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
                : null) || (err instanceof Error ? err.message : 'Unable to join the Tatkal waiting room. Please try again.');
        setState((prev) => ({
          ...prev,
          status: 'ERROR',
          error: msg,
          isReconnecting: false,
        }));
      } finally {
        joinInFlightRef.current = false;
      }
    },
    [],
  );

  /**
   * Voluntarily leave the queue.
   */
  const leave = useCallback(async () => {
    const token = state.queueToken;
    const jId = state.journeyId ?? paramsRef.current.journeyId;
    setState((prev) => ({
      ...prev,
      status: 'LEFT',
      queueToken: null,
      admissionToken: null,
      isPolling: false,
      isReconnecting: false,
      reconnectAttempts: 0,
    }));

    if (token || jId) {
      try {
        await waitingRoomApi.leaveQueue({ queueToken: token ?? undefined, journeyId: jId });
      } catch {
        // Leaving is best-effort on backend
      }
    }
  }, [state.queueToken, state.journeyId]);

  /**
   * Manual retry on connection failure.
   */
  const retry = useCallback(async () => {
    const activeJourneyId = state.journeyId ?? paramsRef.current.journeyId;
    if (state.queueToken && state.status !== 'LEFT') {
      // Try immediate poll with existing token
      setState((prev) => ({
        ...prev,
        error: null,
        isReconnecting: true,
        reconnectAttempts: 0,
      }));
      try {
        const res = await waitingRoomApi.getQueueStatus(state.queueToken, activeJourneyId);
        if (unmountedRef.current) return;

        if (res.status === 'ADMITTED') {
          setState({
            status: 'ADMITTED',
            journeyId: res.journeyId ?? activeJourneyId ?? null,
            queueToken: res.queueToken ?? state.queueToken,
            admissionToken: res.admissionToken ?? res.admissionPass ?? res.queueToken ?? state.queueToken,
            position: 0,
            totalInQueue: res.totalInQueue ?? 0,
            estimatedWaitSeconds: 0,
            expiresAt: res.expiresAt ?? res.admittedUntil ?? null,
            admittedAt: res.admittedAt ?? new Date().toISOString(),
            isPolling: false,
            isReconnecting: false,
            reconnectAttempts: 0,
            error: null,
            lastUpdated: Date.now(),
          });
        } else if (res.status === 'EXPIRED') {
          setState((prev) => ({
            ...prev,
            status: 'EXPIRED',
            queueToken: null,
            admissionToken: null,
            isPolling: false,
            isReconnecting: false,
            reconnectAttempts: 0,
            error: null,
          }));
        } else if (res.status === 'LEFT') {
          setState((prev) => ({
            ...prev,
            status: 'LEFT',
            queueToken: null,
            admissionToken: null,
            isPolling: false,
            isReconnecting: false,
            reconnectAttempts: 0,
            error: null,
          }));
        } else {
          setState((prev) => ({
            ...prev,
            status: 'QUEUED',
            journeyId: res.journeyId ?? activeJourneyId ?? null,
            position: res.position,
            totalInQueue: res.totalInQueue ?? prev.totalInQueue,
            estimatedWaitSeconds: res.estimatedWaitSeconds ?? null,
            expiresAt: res.expiresAt ?? res.admittedUntil ?? null,
            isPolling: false,
            isReconnecting: false,
            reconnectAttempts: 0,
            error: null,
            lastUpdated: Date.now(),
          }));
        }
        return;
      } catch {
        // If polling existing token failed, re-join afresh
      }
    }
    await join();
  }, [state.queueToken, state.status, state.journeyId, join]);

  /**
   * Auto-join effect on initial mount.
   */
  useEffect(() => {
    unmountedRef.current = false;
    if (autoJoin && (journeyId || (trainId && journeyDate))) {
      void join({ trainId, journeyDate, journeyId, quota });
    }
    return () => {
      unmountedRef.current = true;
    };
  }, [autoJoin, trainId, journeyDate, journeyId, quota, join]);

  /**
   * Polling effect when state is QUEUED.
   */
  useEffect(() => {
    if (state.status !== 'QUEUED' || !state.queueToken) {
      return;
    }

    const currentToken = state.queueToken;
    const currentJourneyId = state.journeyId ?? paramsRef.current.journeyId;

    const intervalId = setInterval(async () => {
      if (pollInFlightRef.current || unmountedRef.current) return;
      pollInFlightRef.current = true;

      setState((prev) => ({ ...prev, isPolling: true }));

      try {
        const res = await waitingRoomApi.getQueueStatus(currentToken, currentJourneyId);
        if (unmountedRef.current) return;

        if (res.status === 'ADMITTED') {
          setState({
            status: 'ADMITTED',
            journeyId: res.journeyId ?? currentJourneyId ?? null,
            queueToken: res.queueToken ?? currentToken,
            admissionToken: res.admissionToken ?? res.admissionPass ?? res.queueToken ?? currentToken,
            position: 0,
            totalInQueue: res.totalInQueue ?? 0,
            estimatedWaitSeconds: 0,
            expiresAt: res.expiresAt ?? res.admittedUntil ?? null,
            admittedAt: res.admittedAt ?? new Date().toISOString(),
            isPolling: false,
            isReconnecting: false,
            reconnectAttempts: 0,
            error: null,
            lastUpdated: Date.now(),
          });
        } else if (res.status === 'EXPIRED') {
          setState((prev) => ({
            ...prev,
            status: 'EXPIRED',
            queueToken: null,
            admissionToken: null,
            isPolling: false,
            isReconnecting: false,
            reconnectAttempts: 0,
            error: null,
          }));
        } else if (res.status === 'LEFT') {
          setState((prev) => ({
            ...prev,
            status: 'LEFT',
            queueToken: null,
            admissionToken: null,
            isPolling: false,
            isReconnecting: false,
            reconnectAttempts: 0,
            error: null,
          }));
        } else {
          setState((prev) => ({
            ...prev,
            status: 'QUEUED',
            journeyId: res.journeyId ?? currentJourneyId ?? null,
            position: res.position,
            totalInQueue: res.totalInQueue ?? prev.totalInQueue,
            estimatedWaitSeconds: res.estimatedWaitSeconds ?? null,
            expiresAt: res.expiresAt ?? res.admittedUntil ?? null,
            isPolling: false,
            isReconnecting: false,
            reconnectAttempts: 0,
            error: null,
            lastUpdated: Date.now(),
          }));
        }
      } catch (err: unknown) {
        if (unmountedRef.current) return;

        const status =
          err && typeof err === 'object' && 'response' in err
            ? (err as { response?: { status?: number } }).response?.status
            : null;
        if (status === 401) {
          setState((prev) => ({
            ...prev,
            status: 'ERROR',
            error: 'Authentication required. Please sign in to join the Tatkal waiting room.',
            isPolling: false,
            isReconnecting: false,
          }));
          return;
        }

        setState((prev) => {
          const nextAttempts = prev.reconnectAttempts + 1;
          if (nextAttempts >= maxReconnectAttempts) {
            return {
              ...prev,
              status: 'ERROR',
              error: 'Connection to waiting room lost after repeated attempts. Please retry.',
              isPolling: false,
              isReconnecting: false,
              reconnectAttempts: nextAttempts,
            };
          }
          return {
            ...prev,
            isPolling: false,
            isReconnecting: true,
            reconnectAttempts: nextAttempts,
          };
        });
      } finally {
        pollInFlightRef.current = false;
      }
    }, pollingIntervalMs);

    return () => {
      clearInterval(intervalId);
    };
  }, [state.status, state.queueToken, state.journeyId, pollingIntervalMs, maxReconnectAttempts]);

  return {
    state,
    join,
    leave,
    retry,
  };
}

/**
 * Honest wait time formatting utility:
 * Only formats when backend returns a positive number of seconds.
 * Does NOT generate fake client-side countdowns.
 */
export function formatEstimatedWait(seconds: number | null | undefined): {
  hasEstimate: boolean;
  displayText: string;
} {
  if (seconds === null || seconds === undefined || seconds < 0) {
    return {
      hasEstimate: false,
      displayText: 'Estimating wait time based on queue velocity...',
    };
  }

  if (seconds === 0) {
    return {
      hasEstimate: true,
      displayText: 'Immediate admission',
    };
  }

  if (seconds < 60) {
    return {
      hasEstimate: true,
      displayText: 'Less than 1 minute',
    };
  }

  const minutes = Math.round(seconds / 60);
  if (minutes < 60) {
    return {
      hasEstimate: true,
      displayText: `~${minutes} ${minutes === 1 ? 'min' : 'mins'}`,
    };
  }

  const hours = Math.floor(minutes / 60);
  const remainingMins = minutes % 60;
  return {
    hasEstimate: true,
    displayText: `~${hours} hr ${remainingMins} min`,
  };
}
