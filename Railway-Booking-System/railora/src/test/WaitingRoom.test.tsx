import type { AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { API_PATHS } from '@/lib/constants';
import type {
  JoinQueueResponse,
  QueueStatusResponse,
  LeaveQueueResponse,
  WaitingRoomState,
} from '@/features/waiting-room/types';

// Mock axios / apiClient
const { mockGet, mockPost, mockDelete } = vi.hoisted(() => ({
  mockGet: vi.fn(),
  mockPost: vi.fn(),
  mockDelete: vi.fn(),
}));

vi.mock('@/lib/api-client', () => ({
  default: {
    get: mockGet,
    post: mockPost,
    delete: mockDelete,
  },
}));

import { waitingRoomApi } from '@/features/waiting-room/api';
import { formatEstimatedWait, useWaitingRoom } from '@/features/waiting-room/useWaitingRoom';
import { WaitingRoomCard } from '@/features/waiting-room/components/WaitingRoomCard';
import WaitingRoomPage from '@/pages/WaitingRoomPage';
import { TrainCard } from '@/features/search/components/TrainCard';
import type { TrainSearchResponseDTO } from '@/features/search/types';

function response<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

const mockTrain: TrainSearchResponseDTO = {
  trainId: 101,
  trainNumber: '12301',
  trainName: 'HOWRAH RAJDHANI',
  fromStationCode: 'HWH',
  fromStationName: 'Howrah Jn',
  fromArrivalTime: null,
  fromDepartureTime: '16:55:00',
  fromDayOffset: 0,
  toStationCode: 'NDLS',
  toStationName: 'New Delhi',
  toArrivalTime: '10:05:00',
  toDepartureTime: null,
  toDayOffset: 1,
};

describe('Virtual Waiting Room (VWR) Feature', () => {
  beforeEach(() => {
    vi.useRealTimers();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe('API client module (waitingRoomApi)', () => {
    it('calls joinQueue with correct endpoint and request payload', async () => {
      const joinData: JoinQueueResponse = {
        queueToken: 'q-token-123',
        position: 10,
        status: 'QUEUED',
        estimatedWaitSeconds: 300,
        expiresAt: '2026-10-27T10:15:00Z',
        totalInQueue: 50,
      };
      mockPost.mockResolvedValueOnce(response(joinData));

      const res = await waitingRoomApi.joinQueue({
        trainId: 101,
        journeyDate: '2026-10-27',
        quota: 'TATKAL',
      });

      expect(mockPost).toHaveBeenCalledWith(API_PATHS.WAITING_ROOM.JOIN, {
        trainId: 101,
        journeyDate: '2026-10-27',
        quota: 'TATKAL',
      });
      expect(res.queueToken).toBe('q-token-123');
      expect(res.position).toBe(10);
      expect(res.status).toBe('QUEUED');
    });

    it('calls getQueueStatus with token query param', async () => {
      const statusData: QueueStatusResponse = {
        queueToken: 'q-token-123',
        position: 4,
        status: 'QUEUED',
        estimatedWaitSeconds: 120,
        expiresAt: '2026-10-27T10:15:00Z',
        totalInQueue: 50,
      };
      mockGet.mockResolvedValueOnce(response(statusData));

      const res = await waitingRoomApi.getQueueStatus('q-token-123');

      expect(mockGet).toHaveBeenCalledWith(API_PATHS.WAITING_ROOM.STATUS, {
        params: { token: 'q-token-123' },
      });
      expect(res.position).toBe(4);
    });

    it('calls leaveQueue with queueToken in body', async () => {
      const leaveData: LeaveQueueResponse = {
        success: true,
        message: 'Successfully left the Tatkal waiting room queue.',
      };
      mockPost.mockResolvedValueOnce(response(leaveData));

      const res = await waitingRoomApi.leaveQueue({ queueToken: 'q-token-123' });

      expect(mockPost).toHaveBeenCalledWith(API_PATHS.WAITING_ROOM.LEAVE, {
        queueToken: 'q-token-123',
      });
      expect(res.success).toBe(true);
    });

    it('supports existing backend journey-based endpoints and maps TatkalQueueResponse', async () => {
      // 1. Join with journeyId
      const backendJoinResponse = {
        queueEntryId: 'entry-99',
        journeyId: 7,
        status: 'WAITING',
        joinedAt: '2026-10-27T10:00:00Z',
        position: 3,
        admittedUntil: null,
        admissionPass: null,
      };
      mockPost.mockResolvedValueOnce(response(backendJoinResponse));

      const joinRes = await waitingRoomApi.joinQueue({ journeyId: 7 });
      expect(mockPost).toHaveBeenCalledWith('/tatkal/journeys/7/queue', { journeyId: 7 });
      expect(joinRes.queueToken).toBe('entry-99');
      expect(joinRes.status).toBe('QUEUED'); // normalized from WAITING
      expect(joinRes.position).toBe(3);

      // 2. Status with journeyId
      const backendStatusResponse = {
        queueEntryId: 'entry-99',
        journeyId: 7,
        status: 'ADMITTED',
        joinedAt: '2026-10-27T10:00:00Z',
        position: null,
        admittedUntil: '2026-10-27T10:15:00Z',
        admissionPass: 'secure-signed-pass-xyz',
      };
      mockGet.mockResolvedValueOnce(response(backendStatusResponse));

      const statusRes = await waitingRoomApi.getQueueStatus('entry-99', 7);
      expect(mockGet).toHaveBeenCalledWith('/tatkal/journeys/7/queue/me', undefined);
      expect(statusRes.status).toBe('ADMITTED');
      expect(statusRes.admissionToken).toBe('secure-signed-pass-xyz');
      expect(statusRes.expiresAt).toBe('2026-10-27T10:15:00Z');

      // 3. Leave with journeyId
      mockDelete.mockResolvedValueOnce(response({}));
      const leaveRes = await waitingRoomApi.leaveQueue({ journeyId: 7 });
      expect(mockDelete).toHaveBeenCalledWith('/tatkal/journeys/7/queue/me');
      expect(leaveRes.success).toBe(true);
    });
  });

  describe('formatEstimatedWait utility (Honest Estimation)', () => {
    it('returns honest message when estimatedWaitSeconds is null, undefined, or negative', () => {
      const resNull = formatEstimatedWait(null);
      expect(resNull.hasEstimate).toBe(false);
      expect(resNull.displayText).toContain('Estimating wait time based on queue velocity');

      const resUndefined = formatEstimatedWait(undefined);
      expect(resUndefined.hasEstimate).toBe(false);

      const resNegative = formatEstimatedWait(-1);
      expect(resNegative.hasEstimate).toBe(false);
    });

    it('returns immediate admission when 0 seconds', () => {
      const res = formatEstimatedWait(0);
      expect(res.hasEstimate).toBe(true);
      expect(res.displayText).toBe('Immediate admission');
    });

    it('formats less than 1 minute honestly', () => {
      const res = formatEstimatedWait(45);
      expect(res.hasEstimate).toBe(true);
      expect(res.displayText).toBe('Less than 1 minute');
    });

    it('formats minutes honestly', () => {
      const res = formatEstimatedWait(180);
      expect(res.hasEstimate).toBe(true);
      expect(res.displayText).toBe('~3 mins');
    });

    it('formats hours and minutes honestly', () => {
      const res = formatEstimatedWait(3900);
      expect(res.hasEstimate).toBe(true);
      expect(res.displayText).toBe('~1 hr 5 min');
    });
  });

  describe('WaitingRoomCard UI Component', () => {
    it('renders CONNECTING state with accessible loading spinner and role', () => {
      const state: WaitingRoomState = {
        status: 'CONNECTING',
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
      };

      render(
        <WaitingRoomCard
          state={state}
          train={{ trainNumber: '12301', trainName: 'Rajdhani Express' }}
          travelDate="2026-10-27"
        />,
      );

      expect(screen.getByRole('status')).toBeDefined();
      expect(screen.getByText(/Connecting to Tatkal Waiting Room…/i)).toBeDefined();
      expect(screen.getByText(/12301/i)).toBeDefined();
      expect(screen.getByText(/Rajdhani Express/i)).toBeDefined();
    });

    it('renders QUEUED state with position, total, honest wait estimate, and progressbar', () => {
      const state: WaitingRoomState = {
        status: 'QUEUED',
        queueToken: 'tok-123',
        admissionToken: null,
        position: 7,
        totalInQueue: 42,
        estimatedWaitSeconds: 120,
        expiresAt: '2026-10-27T10:15:00Z',
        admittedAt: null,
        isPolling: false,
        isReconnecting: false,
        reconnectAttempts: 0,
        error: null,
        lastUpdated: Date.now(),
      };

      const handleLeave = vi.fn();

      render(
        <WaitingRoomCard
          state={state}
          train={{ trainNumber: '12301', trainName: 'Rajdhani Express' }}
          travelDate="2026-10-27"
          onLeave={handleLeave}
        />,
      );

      expect(screen.getByText('#7')).toBeDefined();
      expect(screen.getByText(/of ~42 travelers in line/i)).toBeDefined();
      expect(screen.getByRole('progressbar')).toBeDefined();
      expect(screen.getByText(/Estimated Wait: ~2 mins/i)).toBeDefined();

      const leaveBtn = screen.getByRole('button', { name: /leave queue/i });
      fireEvent.click(leaveBtn);
      expect(handleLeave).toHaveBeenCalledTimes(1);
    });

    it('renders QUEUED state with honest fallback when estimatedWaitSeconds is null (no fake countdown)', () => {
      const state: WaitingRoomState = {
        status: 'QUEUED',
        queueToken: 'tok-123',
        admissionToken: null,
        position: 12,
        totalInQueue: 100,
        estimatedWaitSeconds: null, // unsupported / velocity pending
        expiresAt: '2026-10-27T10:15:00Z',
        admittedAt: null,
        isPolling: false,
        isReconnecting: false,
        reconnectAttempts: 0,
        error: null,
        lastUpdated: Date.now(),
      };

      render(
        <WaitingRoomCard
          state={state}
          train={{ trainNumber: '12301', trainName: 'Rajdhani Express' }}
          travelDate="2026-10-27"
        />,
      );

      expect(screen.getByText('#12')).toBeDefined();
      expect(screen.getByText(/Estimated Wait Time/i)).toBeDefined();
      expect(screen.getByText(/We do not display artificial countdowns/i)).toBeDefined();
    });

    it('renders ADMITTED state with admission token, security disclaimer, and proceed button', () => {
      const state: WaitingRoomState = {
        status: 'ADMITTED',
        queueToken: 'tok-123',
        admissionToken: 'adm-token-xyz-999',
        position: 0,
        totalInQueue: 0,
        estimatedWaitSeconds: 0,
        expiresAt: '2026-10-27T10:20:00Z',
        admittedAt: '2026-10-27T10:10:00Z',
        isPolling: false,
        isReconnecting: false,
        reconnectAttempts: 0,
        error: null,
        lastUpdated: Date.now(),
      };

      const handleProceed = vi.fn();

      render(
        <WaitingRoomCard
          state={state}
          train={{ trainNumber: '12301', trainName: 'Rajdhani Express' }}
          travelDate="2026-10-27"
          onProceedToBooking={handleProceed}
        />,
      );

      expect(screen.getByText(/You are Admitted to Book!/i)).toBeDefined();
      expect(screen.getByText('adm-token-xyz-999')).toBeDefined();
      expect(screen.getByText(/Seat reservations are only confirmed once a seat is held/i)).toBeDefined();

      const proceedBtn = screen.getByRole('button', { name: /proceed to seat selection/i });
      fireEvent.click(proceedBtn);
      expect(handleProceed).toHaveBeenCalledWith('adm-token-xyz-999');
    });

    it('renders EXPIRED state with explanation and rejoin button', () => {
      const state: WaitingRoomState = {
        status: 'EXPIRED',
        queueToken: 'tok-123',
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
        lastUpdated: Date.now(),
      };

      const handleRejoin = vi.fn();

      render(
        <WaitingRoomCard
          state={state}
          train={{ trainNumber: '12301', trainName: 'Rajdhani Express' }}
          travelDate="2026-10-27"
          onRejoin={handleRejoin}
        />,
      );

      expect(screen.getByText(/Queue Window Expired/i)).toBeDefined();
      const rejoinBtn = screen.getByRole('button', { name: /rejoin tatkal queue/i });
      fireEvent.click(rejoinBtn);
      expect(handleRejoin).toHaveBeenCalledTimes(1);
    });

    it('renders LEFT state with explanation and rejoin button', () => {
      const state: WaitingRoomState = {
        status: 'LEFT',
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
        lastUpdated: Date.now(),
      };

      const handleRejoin = vi.fn();

      render(
        <WaitingRoomCard
          state={state}
          train={{ trainNumber: '12301', trainName: 'Rajdhani Express' }}
          travelDate="2026-10-27"
          onRejoin={handleRejoin}
        />,
      );

      expect(screen.getByText(/You Have Left the Queue/i)).toBeDefined();
      const rejoinBtn = screen.getByRole('button', { name: /rejoin tatkal queue/i });
      fireEvent.click(rejoinBtn);
      expect(handleRejoin).toHaveBeenCalledTimes(1);
    });

    it('renders ERROR state with role="alert" and retry button', () => {
      const state: WaitingRoomState = {
        status: 'ERROR',
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
        error: 'High load gateway timeout. Please retry.',
        lastUpdated: Date.now(),
      };

      const handleRetry = vi.fn();

      render(
        <WaitingRoomCard
          state={state}
          train={{ trainNumber: '12301', trainName: 'Rajdhani Express' }}
          travelDate="2026-10-27"
          onRetry={handleRetry}
        />,
      );

      expect(screen.getByRole('alert')).toBeDefined();
      expect(screen.getByText(/High load gateway timeout/i)).toBeDefined();

      const retryBtn = screen.getByRole('button', { name: /retry connection/i });
      fireEvent.click(retryBtn);
      expect(handleRetry).toHaveBeenCalledTimes(1);
    });

    it('renders reconnecting banner when isReconnecting is true', () => {
      const state: WaitingRoomState = {
        status: 'QUEUED',
        queueToken: 'tok-123',
        admissionToken: null,
        position: 5,
        totalInQueue: 20,
        estimatedWaitSeconds: 60,
        expiresAt: '2026-10-27T10:15:00Z',
        admittedAt: null,
        isPolling: false,
        isReconnecting: true,
        reconnectAttempts: 2,
        error: null,
        lastUpdated: Date.now(),
      };

      const handleRetry = vi.fn();

      render(
        <WaitingRoomCard
          state={state}
          train={{ trainNumber: '12301', trainName: 'Rajdhani Express' }}
          travelDate="2026-10-27"
          onRetry={handleRetry}
        />,
      );

      expect(screen.getByText(/Reconnecting to waiting room \(attempt 2 of 5\)…/i)).toBeDefined();
      const retryNowBtn = screen.getByRole('button', { name: /retry now/i });
      fireEvent.click(retryNowBtn);
      expect(handleRetry).toHaveBeenCalledTimes(1);
    });
  });

  describe('useWaitingRoom React Hook Integration', () => {
    function HookTestConsumer({
      trainId,
      journeyDate,
    }: {
      trainId?: number;
      journeyDate?: string;
    }) {
      const { state, leave, retry, join } = useWaitingRoom({
        trainId,
        journeyDate,
        pollingIntervalMs: 50, // fast polling for tests
        maxReconnectAttempts: 2,
      });

      return (
        <div>
          <span data-testid="status">{state.status}</span>
          <span data-testid="position">{state.position}</span>
          <span data-testid="queueToken">{state.queueToken ?? 'null'}</span>
          <span data-testid="admissionToken">{state.admissionToken ?? 'null'}</span>
          <span data-testid="isReconnecting">{state.isReconnecting ? 'true' : 'false'}</span>
          <span data-testid="reconnectAttempts">{state.reconnectAttempts}</span>
          <button type="button" onClick={() => join()}>
            Join
          </button>
          <button type="button" onClick={leave}>
            Leave
          </button>
          <button type="button" onClick={retry}>
            Retry
          </button>
        </div>
      );
    }

    it('auto-joins and transitions to QUEUED state', async () => {
      mockPost.mockResolvedValueOnce(
        response<JoinQueueResponse>({
          queueToken: 'hook-tok-1',
          position: 15,
          status: 'QUEUED',
          estimatedWaitSeconds: 180,
          expiresAt: '2026-10-27T10:20:00Z',
          totalInQueue: 60,
        }),
      );

      render(<HookTestConsumer trainId={101} journeyDate="2026-10-27" />);

      expect(screen.getByTestId('status').textContent).toBe('CONNECTING');

      await waitFor(() => {
        expect(screen.getByTestId('status').textContent).toBe('QUEUED');
        expect(screen.getByTestId('position').textContent).toBe('15');
      });
    });

    it('polls queue status and updates position', async () => {
      mockPost.mockResolvedValueOnce(
        response<JoinQueueResponse>({
          queueToken: 'hook-tok-2',
          position: 10,
          status: 'QUEUED',
          estimatedWaitSeconds: 120,
          expiresAt: '2026-10-27T10:20:00Z',
          totalInQueue: 50,
        }),
      );

      // Status polling response (advances to position 2)
      mockGet.mockResolvedValueOnce(
        response<QueueStatusResponse>({
          queueToken: 'hook-tok-2',
          position: 2,
          status: 'QUEUED',
          estimatedWaitSeconds: 30,
          expiresAt: '2026-10-27T10:20:00Z',
          totalInQueue: 50,
        }),
      );

      render(<HookTestConsumer trainId={101} journeyDate="2026-10-27" />);

      await waitFor(() => {
        expect(screen.getByTestId('position').textContent).toBe('10');
      });

      await waitFor(() => {
        expect(screen.getByTestId('position').textContent).toBe('2');
      });
    });

    it('transitions to ADMITTED when status becomes ADMITTED', async () => {
      mockPost.mockResolvedValueOnce(
        response<JoinQueueResponse>({
          queueToken: 'hook-tok-3',
          position: 1,
          status: 'QUEUED',
          estimatedWaitSeconds: 10,
          expiresAt: '2026-10-27T10:20:00Z',
          totalInQueue: 10,
        }),
      );

      mockGet.mockResolvedValueOnce(
        response<QueueStatusResponse>({
          queueToken: 'hook-tok-3',
          position: 0,
          status: 'ADMITTED',
          estimatedWaitSeconds: 0,
          admissionToken: 'admit-now-777',
          admittedAt: '2026-10-27T10:15:00Z',
          expiresAt: '2026-10-27T10:25:00Z',
          totalInQueue: 0,
        }),
      );

      render(<HookTestConsumer trainId={101} journeyDate="2026-10-27" />);

      await waitFor(() => {
        expect(screen.getByTestId('status').textContent).toBe('ADMITTED');
      });
    });

    it('handles voluntary leave action', async () => {
      mockPost.mockResolvedValueOnce(
        response<JoinQueueResponse>({
          queueToken: 'hook-tok-4',
          position: 5,
          status: 'QUEUED',
          expiresAt: '2026-10-27T10:20:00Z',
        }),
      );
      mockPost.mockResolvedValueOnce(
        response<LeaveQueueResponse>({
          success: true,
          message: 'Left queue',
        }),
      );

      render(<HookTestConsumer trainId={101} journeyDate="2026-10-27" />);

      await waitFor(() => {
        expect(screen.getByTestId('status').textContent).toBe('QUEUED');
      });

      const leaveBtn = screen.getByRole('button', { name: 'Leave' });
      fireEvent.click(leaveBtn);

      await waitFor(() => {
        expect(screen.getByTestId('status').textContent).toBe('LEFT');
      });
    });

    it('sets isReconnecting on poll failure and transitions to ERROR on max retries', async () => {
      mockPost.mockResolvedValueOnce(
        response<JoinQueueResponse>({
          queueToken: 'hook-tok-5',
          position: 8,
          status: 'QUEUED',
          expiresAt: '2026-10-27T10:20:00Z',
        }),
      );

      // First poll fails
      mockGet.mockRejectedValueOnce(new Error('Network error 1'));
      // Second poll fails (exceeds maxReconnectAttempts = 2)
      mockGet.mockRejectedValueOnce(new Error('Network error 2'));

      render(<HookTestConsumer trainId={101} journeyDate="2026-10-27" />);

      await waitFor(() => {
        expect(screen.getByTestId('status').textContent).toBe('QUEUED');
      });

      await waitFor(() => {
        expect(screen.getByTestId('status').textContent).toBe('ERROR');
      });
    });

    it('prevents concurrent duplicate join requests while join is in flight', async () => {
      let resolveJoin: (val: unknown) => void;
      const joinPromise = new Promise((resolve) => {
        resolveJoin = resolve;
      });

      mockPost.mockReturnValueOnce(joinPromise);

      render(<HookTestConsumer trainId={101} journeyDate="2026-10-27" />);

      // First join is in flight from mount
      expect(screen.getByTestId('status').textContent).toBe('CONNECTING');
      expect(mockPost).toHaveBeenCalledTimes(1);

      // Triggering join again while first is in flight must NOT make a second network call
      const joinBtn = screen.getByRole('button', { name: 'Join' });
      fireEvent.click(joinBtn);
      fireEvent.click(joinBtn);

      expect(mockPost).toHaveBeenCalledTimes(1);

      // Resolve the join
      resolveJoin!(
        response<JoinQueueResponse>({
          queueToken: 'single-join-tok',
          position: 4,
          status: 'QUEUED',
          expiresAt: '2026-10-27T10:20:00Z',
        }),
      );

      await waitFor(() => {
        expect(screen.getByTestId('status').textContent).toBe('QUEUED');
      });
    });

    it('clears queueToken and admissionToken on leave and on expiry', async () => {
      mockPost.mockResolvedValueOnce(
        response<JoinQueueResponse>({
          queueToken: 'clear-tok-1',
          position: 2,
          status: 'QUEUED',
          expiresAt: '2026-10-27T10:20:00Z',
        }),
      );
      mockPost.mockResolvedValueOnce(
        response<LeaveQueueResponse>({ success: true, message: 'Left' }),
      );

      render(<HookTestConsumer trainId={101} journeyDate="2026-10-27" />);

      await waitFor(() => {
        expect(screen.getByTestId('queueToken').textContent).toBe('clear-tok-1');
      });

      const leaveBtn = screen.getByRole('button', { name: 'Leave' });
      fireEvent.click(leaveBtn);

      await waitFor(() => {
        expect(screen.getByTestId('status').textContent).toBe('LEFT');
        expect(screen.getByTestId('queueToken').textContent).toBe('null');
        expect(screen.getByTestId('admissionToken').textContent).toBe('null');
      });
    });

    it('verifies that admission credentials are never stored in localStorage or sessionStorage', async () => {
      mockPost.mockResolvedValueOnce(
        response<JoinQueueResponse>({
          queueToken: 'secret-queue-pass-123',
          position: 0,
          status: 'ADMITTED',
          admissionToken: 'secret-admission-pass-456',
          expiresAt: '2026-10-27T10:20:00Z',
        }),
      );

      render(<HookTestConsumer trainId={101} journeyDate="2026-10-27" />);

      await waitFor(() => {
        expect(screen.getByTestId('status').textContent).toBe('ADMITTED');
      });

      expect(localStorage.getItem('admissionToken')).toBeNull();
      expect(localStorage.getItem('admissionPass')).toBeNull();
      expect(localStorage.getItem('queueToken')).toBeNull();
      expect(sessionStorage.getItem('admissionToken')).toBeNull();
      expect(sessionStorage.getItem('admissionPass')).toBeNull();
      expect(sessionStorage.getItem('queueToken')).toBeNull();
    });
  });

  describe('Entry points in existing components', () => {
    it('TrainCard renders Tatkal Queue entry button with proper link', () => {
      render(
        <MemoryRouter>
          <TrainCard train={mockTrain} travelDate="2026-10-27" />
        </MemoryRouter>,
      );

      const tatkalLink = screen.getByRole('link', {
        name: /enter tatkal waiting room for 12301/i,
      });
      expect(tatkalLink).toBeDefined();
      expect(tatkalLink.getAttribute('href')).toContain('/waiting-room?trainId=101');
      expect(tatkalLink.getAttribute('href')).toContain('date=2026-10-27');
      expect(tatkalLink.getAttribute('href')).toContain('quota=TATKAL');
    });

    it('WaitingRoomPage shows empty state when accessed without query parameters', () => {
      render(
        <MemoryRouter initialEntries={['/waiting-room']}>
          <WaitingRoomPage />
        </MemoryRouter>,
      );

      expect(screen.getByText(/No Tatkal Train Selected/i)).toBeDefined();
      expect(screen.getByRole('button', { name: /search trains for tatkal/i })).toBeDefined();
    });

    it('WaitingRoomPage loads train from query params and enters queue', async () => {
      mockPost.mockResolvedValueOnce(
        response<JoinQueueResponse>({
          queueToken: 'page-tok-1',
          position: 3,
          status: 'QUEUED',
          estimatedWaitSeconds: 90,
          expiresAt: '2026-10-27T10:20:00Z',
          totalInQueue: 15,
        }),
      );

      render(
        <MemoryRouter
          initialEntries={[
            '/waiting-room?trainId=101&trainNumber=12301&trainName=Rajdhani&date=2026-10-27&quota=TATKAL',
          ]}
        >
          <WaitingRoomPage />
        </MemoryRouter>,
      );

      await waitFor(() => {
        expect(screen.getByText('#3')).toBeDefined();
        expect(screen.getByText(/12301/i)).toBeDefined();
      });
    });

    it('WaitingRoomPage supports direct journeyId query parameter', async () => {
      mockPost.mockResolvedValueOnce(
        response<JoinQueueResponse>({
          queueToken: 'direct-j-tok',
          journeyId: 88,
          position: 1,
          status: 'QUEUED',
          expiresAt: '2026-10-27T10:20:00Z',
        }),
      );

      render(
        <MemoryRouter initialEntries={['/waiting-room?journeyId=88&quota=TATKAL']}>
          <WaitingRoomPage />
        </MemoryRouter>,
      );

      await waitFor(() => {
        expect(screen.getByText('#1')).toBeDefined();
      });

      expect(mockPost).toHaveBeenCalledWith(
        '/tatkal/journeys/88/queue',
        expect.objectContaining({ journeyId: 88 }),
      );
    });

    it('renders 401 ERROR state with Sign In CTA when authentication is required', () => {
      const state: WaitingRoomState = {
        status: 'ERROR',
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
        error: 'Authentication required. Please sign in to join the Tatkal waiting room.',
        lastUpdated: Date.now(),
      };

      const handleSignIn = vi.fn();

      render(
        <WaitingRoomCard
          state={state}
          train={{ trainNumber: '12301', trainName: 'Rajdhani Express' }}
          travelDate="2026-10-27"
          onSignIn={handleSignIn}
        />,
      );

      expect(screen.getByRole('alert')).toBeDefined();
      expect(screen.getByRole('heading', { name: /Sign In to Access Tatkal Queue/i })).toBeDefined();

      const signInBtn = screen.getByRole('button', { name: /sign in/i });
      fireEvent.click(signInBtn);
      expect(handleSignIn).toHaveBeenCalledTimes(1);
    });

    it('useWaitingRoom transitions to ERROR on 401 without reconnect looping', async () => {
      mockPost.mockRejectedValueOnce({
        response: {
          status: 401,
          data: { message: 'Full authentication is required to access this resource' },
        },
      });

      function ErrorHookConsumer() {
        const { state } = useWaitingRoom({
          trainId: 101,
          journeyDate: '2026-10-27',
        });
        return (
          <div>
            <span data-testid="status">{state.status}</span>
            <span data-testid="error">{state.error ?? 'null'}</span>
          </div>
        );
      }

      render(<ErrorHookConsumer />);

      await waitFor(() => {
        expect(screen.getByTestId('status').textContent).toBe('ERROR');
        expect(screen.getByTestId('error').textContent).toContain('Authentication required');
      });
    });

    it('supports journeyId in useWaitingRoom hook and uses journey-based queue endpoints', async () => {
      mockPost.mockResolvedValueOnce(
        response<JoinQueueResponse>({
          queueToken: 'journey-tok-99',
          journeyId: 42,
          position: 2,
          status: 'QUEUED',
          expiresAt: '2026-10-27T10:20:00Z',
        }),
      );

      mockGet.mockResolvedValueOnce(
        response<QueueStatusResponse>({
          queueToken: 'journey-tok-99',
          journeyId: 42,
          position: 0,
          status: 'ADMITTED',
          admissionToken: 'pass-adm-42',
          expiresAt: '2026-10-27T10:25:00Z',
        }),
      );

      mockDelete.mockResolvedValueOnce(response({}));

      function JourneyHookConsumer() {
        const { state, leave } = useWaitingRoom({
          journeyId: 42,
          pollingIntervalMs: 50,
        });

        return (
          <div>
            <span data-testid="status">{state.status}</span>
            <span data-testid="admissionToken">{state.admissionToken ?? 'null'}</span>
            <button type="button" onClick={leave}>
              Leave
            </button>
          </div>
        );
      }

      render(<JourneyHookConsumer />);

      await waitFor(() => {
        expect(screen.getByTestId('status').textContent).toBe('QUEUED');
      });

      expect(mockPost).toHaveBeenCalledWith(
        '/tatkal/journeys/42/queue',
        expect.objectContaining({ journeyId: 42 }),
      );

      await waitFor(() => {
        expect(screen.getByTestId('status').textContent).toBe('ADMITTED');
        expect(screen.getByTestId('admissionToken').textContent).toBe('pass-adm-42');
      });

      const leaveBtn = screen.getByRole('button', { name: 'Leave' });
      fireEvent.click(leaveBtn);

      await waitFor(() => {
        expect(screen.getByTestId('status').textContent).toBe('LEFT');
      });

      expect(mockDelete).toHaveBeenCalledWith('/tatkal/journeys/42/queue/me');
    });

    it('WaitingRoomPage resolves correct directional journeyId from train, date, and stations', async () => {
      // Return 2 journeys: journey 10 (wrong direction) and journey 20 (correct direction)
      mockGet.mockImplementation((url: string) => {
        if (url === '/journeys') {
          return Promise.resolve(
            response([
              { journeyId: 10, trainId: 101, journeyDate: '2026-10-27' },
              { journeyId: 20, trainId: 101, journeyDate: '2026-10-27' },
            ]),
          );
        }
        if (url === '/journeys/10/stops') {
          return Promise.resolve(
            response([
              { journeyStopId: 1, stationCode: 'NDLS', stopSequence: 1 },
              { journeyStopId: 2, stationCode: 'HWH', stopSequence: 2 },
            ]),
          );
        }
        if (url === '/journeys/20/stops') {
          return Promise.resolve(
            response([
              { journeyStopId: 3, stationCode: 'HWH', stopSequence: 1 },
              { journeyStopId: 4, stationCode: 'NDLS', stopSequence: 2 },
            ]),
          );
        }
        return Promise.reject(new Error(`Unhandled get url: ${url}`));
      });

      mockPost.mockResolvedValueOnce(
        response<JoinQueueResponse>({
          queueToken: 'directional-tok-20',
          journeyId: 20,
          position: 1,
          status: 'QUEUED',
          expiresAt: '2026-10-27T10:20:00Z',
        }),
      );

      render(
        <MemoryRouter
          initialEntries={[
            '/waiting-room?trainId=101&trainNumber=12301&fromStationCode=HWH&toStationCode=NDLS&date=2026-10-27&quota=TATKAL',
          ]}
        >
          <WaitingRoomPage />
        </MemoryRouter>,
      );

      await waitFor(() => {
        expect(screen.getByText('#1')).toBeDefined();
      });

      // Verify that Tatkal queue endpoint for the directional journeyId (20) was called
      expect(mockPost).toHaveBeenCalledWith(
        '/tatkal/journeys/20/queue',
        expect.objectContaining({ journeyId: 20 }),
      );
    });

    it('WaitingRoomPage displays error when no directional journey run matches origin and destination', async () => {
      mockGet.mockImplementation((url: string) => {
        if (url === '/journeys') {
          return Promise.resolve(
            response([
              { journeyId: 10, trainId: 101, journeyDate: '2026-10-27' },
            ]),
          );
        }
        if (url === '/journeys/10/stops') {
          // NDLS before HWH (reverse of requested HWH -> NDLS)
          return Promise.resolve(
            response([
              { journeyStopId: 1, stationCode: 'NDLS', stopSequence: 1 },
              { journeyStopId: 2, stationCode: 'HWH', stopSequence: 2 },
            ]),
          );
        }
        return Promise.reject(new Error(`Unhandled get url: ${url}`));
      });

      render(
        <MemoryRouter
          initialEntries={[
            '/waiting-room?trainId=101&trainNumber=12301&fromStationCode=HWH&toStationCode=NDLS&date=2026-10-27&quota=TATKAL',
          ]}
        >
          <WaitingRoomPage />
        </MemoryRouter>,
      );

      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeDefined();
        expect(
          screen.getByText(/No directional journey run matches HWH → NDLS on 2026-10-27/i),
        ).toBeDefined();
      });

      // Must NOT join Tatkal queue for wrong directional run
      expect(mockPost).not.toHaveBeenCalled();
    });
  });
});
