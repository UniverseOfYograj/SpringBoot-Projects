import apiClient from '@/lib/api-client';
import { API_PATHS } from '@/lib/constants';
import type {
  JoinQueueRequest,
  JoinQueueResponse,
  QueueStatusResponse,
  LeaveQueueRequest,
  LeaveQueueResponse,
} from './types';

/**
 * Dedicated API module for Railora Virtual Waiting Room.
 *
 * Implements the agreed Phase D Virtual Waiting Room contract:
 * - High-concurrency Tatkal queueing backed by Redis ZSET.
 * - Tokens: queueToken (for polling position), admissionToken (for booking window).
 * - Real admission enforcement is owned by backend.
 */
export const waitingRoomApi = {
  /**
   * Enter the Tatkal waiting room queue for a train and journey date (or direct journeyId).
   */
  async joinQueue(request: JoinQueueRequest): Promise<JoinQueueResponse> {
    const url = request.journeyId
      ? API_PATHS.TATKAL.QUEUE(request.journeyId)
      : API_PATHS.WAITING_ROOM.JOIN;
    const response = await apiClient.post<JoinQueueResponse>(url, request);
    const data = response.data;
    return {
      ...data,
      queueToken: data.queueToken || data.queueEntryId || '',
      status: data.status === 'WAITING' ? 'QUEUED' : data.status,
      admissionToken: data.admissionPass || data.admissionToken,
      expiresAt: data.admittedUntil || data.expiresAt || '',
    };
  },

  /**
   * Poll current queue position and admission status using queue token (or journeyId).
   */
  async getQueueStatus(queueToken: string, journeyId?: number): Promise<QueueStatusResponse> {
    const url = journeyId
      ? API_PATHS.TATKAL.STATUS(journeyId)
      : API_PATHS.WAITING_ROOM.STATUS;
    const config = journeyId ? undefined : { params: { token: queueToken } };
    const response = await apiClient.get<QueueStatusResponse>(url, config);
    const data = response.data;
    return {
      ...data,
      queueToken: data.queueToken || data.queueEntryId || queueToken,
      status: data.status === 'WAITING' ? 'QUEUED' : data.status,
      admissionToken: data.admissionPass || data.admissionToken,
      expiresAt: data.admittedUntil || data.expiresAt || '',
    };
  },

  /**
   * Voluntarily leave the waiting room queue.
   */
  async leaveQueue(request: LeaveQueueRequest): Promise<LeaveQueueResponse> {
    if (request.journeyId) {
      await apiClient.delete(API_PATHS.TATKAL.LEAVE(request.journeyId));
      return { success: true, message: 'Successfully left the Tatkal queue.' };
    }
    const response = await apiClient.post<LeaveQueueResponse>(
      API_PATHS.WAITING_ROOM.LEAVE,
      request,
    );
    return response.data;
  },
};
