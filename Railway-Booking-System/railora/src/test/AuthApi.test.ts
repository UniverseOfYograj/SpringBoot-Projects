import type { AxiosResponse } from 'axios';
import { afterEach, describe, expect, it, vi } from 'vitest';

const { mockGet, mockPost } = vi.hoisted(() => ({
  mockGet: vi.fn(),
  mockPost: vi.fn(),
}));

vi.mock('@/lib/api-client', () => ({
  default: {
    get: mockGet,
    post: mockPost,
  },
}));

import { authApi } from '@/features/auth/api';

function axiosError(status: number, data: unknown) {
  return Object.assign(new Error('Request failed'), {
    isAxiosError: true,
    response: { status, data },
  });
}

function response<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

afterEach(() => {
  vi.clearAllMocks();
});

describe('authentication API', () => {
  it('sends the verified login payload and returns the auth response', async () => {
    const authResponse = {
      accessToken: 'access',
      refreshToken: 'refresh',
      tokenType: 'Bearer',
      expiresIn: 60000,
      message: 'Authentication successful',
    };
    mockPost.mockResolvedValueOnce(response(authResponse));

    await expect(authApi.login({ username: 'traveller@example.com', password: 'secret' }))
      .resolves.toEqual(authResponse);
    expect(mockPost).toHaveBeenCalledWith('/auth/login', {
      username: 'traveller@example.com',
      password: 'secret',
    });
  });

  it('returns a useful message for failed login without exposing server details', async () => {
    mockPost.mockRejectedValueOnce(axiosError(401, { message: 'private exception detail' }));

    await expect(authApi.login({ username: 'rider', password: 'wrong' }))
      .rejects.toMatchObject({
        name: 'AuthApiError',
        code: 'invalid-credentials',
        message: 'The username or password is incorrect.',
      });
  });

  it('maps duplicate registration responses to a safe user-facing message', async () => {
    mockPost.mockRejectedValueOnce(axiosError(500, { message: 'Username already exists' }));

    await expect(authApi.register({
      username: 'rider',
      fullName: 'Rail Rider',
      email: 'rider@example.com',
      phoneNumber: '',
      password: 'secret',
    })).rejects.toMatchObject({
      name: 'AuthApiError',
      code: 'duplicate-registration',
      message: 'That username or email is already registered. Try signing in or use different details.',
    });
  });

  it('uses the refresh token request body from the backend contract', async () => {
    const authResponse = {
      accessToken: 'new-access',
      refreshToken: 'refresh',
      tokenType: 'Bearer',
      expiresIn: 60000,
      message: 'Token refreshed successfully',
    };
    mockPost.mockResolvedValueOnce(response(authResponse));

    await expect(authApi.refresh('refresh')).resolves.toEqual(authResponse);
    expect(mockPost).toHaveBeenCalledWith('/auth/refresh', { refreshToken: 'refresh' });
  });

  it('uses a safe account hint for unstructured server-side registration errors', async () => {
    mockPost.mockRejectedValueOnce(axiosError(500, { message: 'database exception detail' }));

    const request = authApi.register({
      username: 'rider',
      fullName: 'Rail Rider',
      email: 'rider@example.com',
      phoneNumber: '',
      password: 'secret',
    });
    await expect(request).rejects.toMatchObject({
      name: 'AuthApiError',
      code: 'request-failed',
      message: 'We could not create your account. Check whether the username or email is already in use, then try again.',
    });
  });

  it('loads the current user with the access-token bearer header', async () => {
    const currentUser = {
      userId: 7,
      username: 'railrider',
      fullName: 'Rail Rider',
      email: 'rider@example.com',
      phoneNumber: null,
      status: 'ACTIVE',
      createdAt: '2026-10-26T00:00:00',
    };
    mockGet.mockResolvedValueOnce(response(currentUser));

    await expect(authApi.getCurrentUser('access-token')).resolves.toEqual(currentUser);
    expect(mockGet).toHaveBeenCalledWith('/users/me', {
      headers: { Authorization: 'Bearer access-token' },
    });
  });
});
