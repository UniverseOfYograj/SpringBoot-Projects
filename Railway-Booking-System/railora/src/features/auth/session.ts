import type { AuthResponse } from './types';

const STORAGE_KEY = 'railora_auth_session';

function loadStoredSession(): AuthResponse | null {
  try {
    if (typeof window === 'undefined' || !window.localStorage) return null;
    const item = window.localStorage.getItem(STORAGE_KEY);
    return item ? (JSON.parse(item) as AuthResponse) : null;
  } catch {
    return null;
  }
}

let currentSession: AuthResponse | null = loadStoredSession();

export function setSession(session: AuthResponse): void {
  currentSession = session;
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    }
  } catch {
    // Graceful fallback for storage quota or restricted environments
  }
}

export function getSession(): AuthResponse | null {
  if (!currentSession) {
    currentSession = loadStoredSession();
  }
  return currentSession;
}

export function getAccessToken(): string | null {
  return getSession()?.accessToken ?? null;
}

export function clearSession(): void {
  currentSession = null;
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    // Graceful fallback
  }
}
