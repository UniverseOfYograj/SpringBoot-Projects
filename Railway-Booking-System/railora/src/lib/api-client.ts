import axios from 'axios';
import { getAccessToken } from '@/features/auth/session';

/**
 * Centralized Axios API client.
 *
 * All requests are made to relative `/api/...` paths.
 * In development, Vite proxies them to http://localhost:8080.
 * In production, the web server must handle the same rewrite.
 *
 * Access tokens are attached from the in-memory authentication session.
 */
const apiClient = axios.create({
  baseURL: '/api',
  headers: {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  },
  timeout: 15_000,
});

// Attach the active in-memory access token to authenticated requests.
apiClient.interceptors.request.use(
  (config) => {
    // TODO (Stage 4): Attach `Authorization: Bearer <token>` from AuthContext.
    const accessToken = getAccessToken();
    if (accessToken) {
      config.headers.Authorization = `Bearer ${accessToken}`;
    }
    return config;
  },
  (error) => Promise.reject(error),
);

// Refresh remains an explicit operation; expired sessions are not silently refreshed.
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    return Promise.reject(error);
  },
);

export default apiClient;
