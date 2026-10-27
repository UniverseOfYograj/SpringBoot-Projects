/**
 * Virtual Waiting Room (VWR) types and data transfer objects.
 *
 * Implements the agreed Phase D Virtual Waiting Room contract:
 * - High-concurrency Tatkal queueing backed by Redis ZSET / queue tokens.
 * - Honest wait-time estimation (no fake client-side countdowns).
 * - Clear distinction between admission (temporary booking window) and confirmed seat allocation.
 */

export type QueueStatus =
  | 'CONNECTING'
  | 'QUEUED'
  | 'ADMITTED'
  | 'EXPIRED'
  | 'LEFT'
  | 'ERROR';

export type QuotaType = 'TATKAL' | 'GENERAL';

export interface JoinQueueRequest {
  trainId?: number;
  journeyDate?: string;
  journeyId?: number;
  quota?: QuotaType;
}

export interface JoinQueueResponse {
  queueToken: string;
  queueEntryId?: string;
  journeyId?: number;
  position: number;
  status: 'QUEUED' | 'ADMITTED' | 'WAITING' | string;
  estimatedWaitSeconds?: number | null;
  expiresAt: string;
  admittedUntil?: string;
  admissionToken?: string;
  admissionPass?: string;
  totalInQueue?: number;
}

export interface QueueStatusResponse {
  queueToken?: string;
  queueEntryId?: string;
  journeyId?: number;
  position: number;
  status: 'QUEUED' | 'ADMITTED' | 'EXPIRED' | 'LEFT' | 'WAITING' | string;
  estimatedWaitSeconds?: number | null;
  admissionToken?: string;
  admissionPass?: string;
  admittedAt?: string;
  admittedUntil?: string;
  expiresAt?: string;
  totalInQueue?: number;
}

export interface LeaveQueueRequest {
  queueToken?: string;
  journeyId?: number;
}

export interface LeaveQueueResponse {
  success: boolean;
  message: string;
}

export interface WaitingRoomState {
  status: QueueStatus;
  journeyId?: number | null;
  queueToken: string | null;
  admissionToken: string | null;
  position: number | null;
  totalInQueue: number | null;
  estimatedWaitSeconds: number | null;
  expiresAt: string | null;
  admittedAt: string | null;
  isPolling: boolean;
  isReconnecting: boolean;
  reconnectAttempts: number;
  error: string | null;
  lastUpdated: number | null;
}
