/**
 * Application-wide constants.
 *
 * No secrets or credentials are defined here.
 * All sensitive config is injected via environment variables or auth interceptors.
 */

// ── Client-side route paths ─────────────────────────────────────────
export const ROUTES = {
  HOME:         '/',
  SEARCH:       '/search',
  BOOKINGS:     '/bookings',
  WAITING_ROOM: '/waiting-room',
  LOGIN:        '/login',
  REGISTER:     '/register',
  PROFILE:      '/profile',
} as const;

// ── API path prefixes (relative — proxied by Vite in dev) ──────────
export const API_PATHS = {
  AUTH: {
    LOGIN:    '/auth/login',
    REGISTER: '/auth/register',
    REFRESH:  '/auth/refresh',
  },
  USERS: {
    ME: '/users/me',
    BY_ID: (id: number) => `/users/${id}`,
  },
  TRAINS: {
    SEARCH: '/trains/search',
  },
  JOURNEYS: {
    LIST:    '/journeys',
    SEARCH:  '/journeys/search',
    FILTER:  '/journeys/filter',
    BY_ID:   (id: number) => `/journeys/${id}`,
    STOPS:   (id: number) => `/journeys/${id}/stops`,
    SEATS:   (id: number) => `/journeys/${id}/seats`,
    AVAILABLE_SEATS: (id: number) => `/journeys/${id}/seats/available`,
    HOLD:    (id: number) => `/journeys/${id}/seats/hold`,
  },
  BOOKINGS: {
    LIST:    '/bookings',
    CREATE:  '/bookings',
    BY_REF:  (ref: string) => `/bookings/${ref}`,
    BY_PNR:  (pnr: string) => `/bookings/pnr/${pnr}`,
    PAY:     (ref: string) => `/bookings/${ref}/pay`,
    SIMULATED_SUMMARY: (ref: string) => `/bookings/${ref}/simulated`,
    SIMULATED_INITIATE: (ref: string) => `/bookings/${ref}/simulated/initiate`,
    SIMULATED_SUCCESS: (ref: string) => `/bookings/${ref}/simulated/success`,
    SIMULATED_FAILURE: (ref: string) => `/bookings/${ref}/simulated/failure`,
    RAZORPAY_ORDER: (ref: string) => `/bookings/${ref}/razorpay/order`,
    RAZORPAY_VERIFY: (ref: string) => `/bookings/${ref}/razorpay/verify`,
    CANCEL:  (ref: string) => `/bookings/${ref}/cancel`,
  },
  WAITING_ROOM: {
    JOIN:   '/vwr/queue/join',
    STATUS: '/vwr/queue/status',
    LEAVE:  '/vwr/queue/leave',
  },
  TATKAL: {
    QUEUE:  (journeyId: number) => `/tatkal/journeys/${journeyId}/queue`,
    STATUS: (journeyId: number) => `/tatkal/journeys/${journeyId}/queue/me`,
    LEAVE:  (journeyId: number) => `/tatkal/journeys/${journeyId}/queue/me`,
  },
} as const;

// ── Design constants ────────────────────────────────────────────────
export const BRAND = {
  NAME: 'Railora',
  TAGLINE: 'Your journey, simplified.',
} as const;
