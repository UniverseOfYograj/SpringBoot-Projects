import axios from 'axios';
import apiClient from '@/lib/api-client';
import { API_PATHS } from '@/lib/constants';
import type {
  AuthResponse,
  LoginCredentials,
  RegistrationDetails,
  UserResponse,
} from './types';

interface ApiErrorBody {
  message?: unknown;
  error?: unknown;
}

export class AuthApiError extends Error {
  constructor(
    message: string,
    public readonly code: 'invalid-credentials' | 'duplicate-registration' | 'request-failed',
  ) {
    super(message);
    this.name = 'AuthApiError';
  }
}

function responseMessage(data: unknown): string {
  if (!data || typeof data !== 'object') return '';
  const body = data as ApiErrorBody;
  return [body.message, body.error]
    .filter((value): value is string => typeof value === 'string')
    .join(' ')
    .toLowerCase();
}

function toAuthApiError(error: unknown, operation: 'login' | 'registration'): AuthApiError {
  if (!axios.isAxiosError(error)) {
    return new AuthApiError('Something went wrong. Please try again.', 'request-failed');
  }

  const status = error.response?.status;
  const message = responseMessage(error.response?.data);
  if (operation === 'login' && status === 401) {
    return new AuthApiError('The username or password is incorrect.', 'invalid-credentials');
  }

  if (
    operation === 'registration' &&
    (status === 409 ||
      message.includes('username already exists') ||
      message.includes('email already exists'))
  ) {
    return new AuthApiError(
      'That username or email is already registered. Try signing in or use different details.',
      'duplicate-registration',
    );
  }

  if (!error.response) {
    return new AuthApiError(
      'Unable to reach Railora right now. Check your connection and try again.',
      'request-failed',
    );
  }

  if (operation === 'registration' && status !== undefined && status >= 500) {
    return new AuthApiError(
      'We could not create your account. Check whether the username or email is already in use, then try again.',
      'request-failed',
    );
  }

  return new AuthApiError(
    operation === 'login'
      ? 'Unable to sign in right now. Please check your details and try again.'
      : 'Unable to create your account right now. Please try again.',
    'request-failed',
  );
}

export const authApi = {
  async login(credentials: LoginCredentials): Promise<AuthResponse> {
    try {
      const response = await apiClient.post<AuthResponse>(API_PATHS.AUTH.LOGIN, credentials);
      return response.data;
    } catch (error) {
      throw toAuthApiError(error, 'login');
    }
  },

  async register(details: RegistrationDetails): Promise<UserResponse> {
    try {
      const response = await apiClient.post<UserResponse>(API_PATHS.AUTH.REGISTER, details);
      return response.data;
    } catch (error) {
      throw toAuthApiError(error, 'registration');
    }
  },

  async getCurrentUser(accessToken: string): Promise<UserResponse> {
    const response = await apiClient.get<UserResponse>(API_PATHS.USERS.ME, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    return response.data;
  },

  async refresh(refreshToken: string): Promise<AuthResponse> {
    const response = await apiClient.post<AuthResponse>(API_PATHS.AUTH.REFRESH, { refreshToken });
    return response.data;
  },
};
