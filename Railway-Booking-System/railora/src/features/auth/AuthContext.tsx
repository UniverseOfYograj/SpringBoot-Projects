import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { authApi } from './api';
import { clearSession, getSession, setSession } from './session';
import type { LoginCredentials, UserResponse } from './types';

type AuthState =
  | { status: 'loading' }
  | { status: 'unauthenticated' }
  | { status: 'authenticated'; user: UserResponse };

interface AuthContextValue {
  state: AuthState;
  login: (credentials: LoginCredentials) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading' });

  useEffect(() => {
    let active = true;
    const session = getSession();
    if (!session) {
      setState({ status: 'unauthenticated' });
      return () => {
        active = false;
      };
    }

    void authApi.getCurrentUser(session.accessToken).then(
      (user) => {
        if (active) setState({ status: 'authenticated', user });
      },
      () => {
        if (active) {
          clearSession();
          setState({ status: 'unauthenticated' });
        }
      },
    );

    return () => {
      active = false;
    };
  }, []);

  const login = useCallback(async (credentials: LoginCredentials) => {
    const session = await authApi.login(credentials);
    setSession(session);
    try {
      const user = await authApi.getCurrentUser(session.accessToken);
      setState({ status: 'authenticated', user });
    } catch (error) {
      clearSession();
      setState({ status: 'unauthenticated' });
      throw error;
    }
  }, []);

  const logout = useCallback(() => {
    clearSession();
    setState({ status: 'unauthenticated' });
  }, []);

  const value = useMemo(() => ({ state, login, logout }), [state, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    return {
      state: { status: 'unauthenticated' },
      login: async () => {},
      logout: () => {},
    };
  }
  return context;
}
