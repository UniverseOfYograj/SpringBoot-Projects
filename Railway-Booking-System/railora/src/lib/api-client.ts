import axios from 'axios';

/**
 * Centralized Axios API client.
 *
 * All requests are made to relative `/api/...` paths.
 * In development, Vite proxies them to http://localhost:8080.
 * In production, the web server must handle the same rewrite.
 *
 * Auth token injection, 401 handling, and refresh-token rotation
 * will be added in Stage 4 via request/response interceptors.
 */
const apiClient = axios.create({
  baseURL: '/api',
  headers: {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  },
  timeout: 15_000,
});

// ── Request interceptor (token injection — Stage 4) ──────────────
apiClient.interceptors.request.use(
  (config) => {
    // TODO (Stage 4): Attach `Authorization: Bearer <token>` from AuthContext.
    return config;
  },
  (error) => Promise.reject(error),
);

// ── Response interceptor (error normalization, 401 refresh — Stage 4) ─
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    // TODO (Stage 4): Intercept 401 to attempt silent token refresh.
    // TODO: Normalize error shape to match ApiErrorResponse from backend.
    return Promise.reject(error);
  },
);

export default apiClient;
