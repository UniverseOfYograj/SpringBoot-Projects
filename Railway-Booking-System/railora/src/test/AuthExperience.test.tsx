import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from '@/features/auth/AuthContext';
import { AuthApiError, authApi } from '@/features/auth/api';
import { clearSession, getAccessToken, setSession } from '@/features/auth/session';
import type { AuthResponse, UserResponse } from '@/features/auth/types';
import Header from '@/components/layout/Header';
import LoginPage from '@/pages/LoginPage';
import RegisterPage from '@/pages/RegisterPage';

vi.mock('@/features/auth/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/auth/api')>();
  return {
    ...actual,
    authApi: {
      getCurrentUser: vi.fn(),
      login: vi.fn(),
      refresh: vi.fn(),
      register: vi.fn(),
    },
  };
});

const mockedAuthApi = vi.mocked(authApi);
const user: UserResponse = {
  userId: 7,
  username: 'railrider',
  fullName: 'Rail Rider',
  email: 'rider@example.com',
  phoneNumber: null,
  status: 'ACTIVE',
  createdAt: '2026-10-26T00:00:00',
};
const tokens: AuthResponse = {
  accessToken: 'access-token',
  refreshToken: 'refresh-token',
  tokenType: 'Bearer',
  expiresIn: 60000,
  message: 'Authentication successful',
};

function renderAuthPage(page: ReactNode) {
  return render(
    <MemoryRouter>
      <AuthProvider>{page}</AuthProvider>
    </MemoryRouter>,
  );
}

function fillRegistrationForm(phoneNumber?: string) {
  fireEvent.change(screen.getByRole('textbox', { name: 'Username' }), {
    target: { value: 'rider' },
  });
  fireEvent.change(screen.getByRole('textbox', { name: 'Full name' }), {
    target: { value: 'Rail Rider' },
  });
  fireEvent.change(screen.getByRole('textbox', { name: 'Email address' }), {
    target: { value: 'rider@example.com' },
  });
  if (phoneNumber) {
    fireEvent.change(screen.getByLabelText(/Phone number/), { target: { value: phoneNumber } });
  }
  fireEvent.change(screen.getByLabelText(/Password/), { target: { value: 'secret' } });
}

function AuthStateControl() {
  const { state, login, logout } = useAuth();
  return (
    <div>
      <output>{state.status === 'authenticated' ? state.user.username : state.status}</output>
      <button type="button" onClick={() => void login({ username: 'railrider', password: 'secret' })}>
        Log in for test
      </button>
      <button type="button" onClick={logout}>Log out for test</button>
    </div>
  );
}

beforeEach(() => {
  clearSession();
  vi.clearAllMocks();
  mockedAuthApi.getCurrentUser.mockResolvedValue(user);
  mockedAuthApi.login.mockResolvedValue(tokens);
  mockedAuthApi.register.mockResolvedValue(user);
});

afterEach(() => {
  clearSession();
});

describe('login and registration pages', () => {
  it('validates required login fields and provides accessible labels', async () => {
    const userEventInstance = userEvent.setup();
    renderAuthPage(<LoginPage />);

    const username = screen.getByRole('textbox', { name: 'Username or email' });
    expect(username).toHaveAttribute('autocomplete', 'username');
    expect(screen.getByLabelText(/Password/)).toHaveAttribute('type', 'password');
    expect(username).toHaveFocus();
    await userEventInstance.tab();
    expect(screen.getByLabelText(/Password/)).toHaveFocus();
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(screen.getByText('Enter your username or email address.')).toBeInTheDocument();
    expect(screen.getByText('Enter your password.')).toBeInTheDocument();
    expect(mockedAuthApi.login).not.toHaveBeenCalled();
  });

  it('validates registration fields and email format before calling the API', async () => {
    renderAuthPage(<RegisterPage />);

    fireEvent.change(screen.getByRole('textbox', { name: 'Username' }), {
      target: { value: 'rider' },
    });
    fireEvent.change(screen.getByRole('textbox', { name: 'Full name' }), {
      target: { value: 'Rail Rider' },
    });
    fireEvent.change(screen.getByRole('textbox', { name: 'Email address' }), {
      target: { value: 'not-an-email' },
    });
    fireEvent.change(screen.getByLabelText(/Password/), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByText('Enter a valid email address.')).toBeInTheDocument();
    expect(mockedAuthApi.register).not.toHaveBeenCalled();
  });

  it('shows a safe duplicate-registration message', async () => {
    mockedAuthApi.register.mockRejectedValueOnce(new AuthApiError(
      'That username or email is already registered. Try signing in or use different details.',
      'duplicate-registration',
    ));
    renderAuthPage(<RegisterPage />);
    fillRegistrationForm();
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/already registered/i);
    expect(screen.getByRole('alert')).not.toHaveTextContent(/runtimeexception|sql|database/i);
  });

  it('sends registration details and navigates to sign in after success', async () => {
    render(
      <MemoryRouter initialEntries={['/register']}>
        <AuthProvider>
          <Routes>
            <Route path="/register" element={<RegisterPage />} />
            <Route path="/login" element={<LoginPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    fillRegistrationForm('5550100');
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByRole('heading', { name: 'Sign in to Railora' })).toBeInTheDocument();
    expect(mockedAuthApi.register).toHaveBeenCalledWith({
      username: 'rider',
      fullName: 'Rail Rider',
      email: 'rider@example.com',
      phoneNumber: '5550100',
      password: 'secret',
    });
    expect(screen.getByRole('status')).toHaveTextContent(/account is ready/i);
  });

  it('navigates to the home route after successful sign in', async () => {
    render(
      <MemoryRouter initialEntries={['/login']}>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/" element={<h1>Railora home</h1>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    fireEvent.change(screen.getByRole('textbox', { name: 'Username or email' }), {
      target: { value: 'rider@example.com' },
    });
    fireEvent.change(screen.getByLabelText(/Password/), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('heading', { name: 'Railora home' })).toBeInTheDocument();
    expect(mockedAuthApi.login).toHaveBeenCalledWith({
      username: 'rider@example.com',
      password: 'secret',
    });
  });

  it('disables login submission while the request is pending', async () => {
    let resolveCurrentUser: (resolvedUser: UserResponse) => void = () => undefined;
    mockedAuthApi.getCurrentUser.mockImplementationOnce(
      () => new Promise((resolve) => { resolveCurrentUser = resolve; }),
    );
    renderAuthPage(<LoginPage />);
    fireEvent.change(screen.getByRole('textbox', { name: 'Username or email' }), {
      target: { value: 'rider' },
    });
    fireEvent.change(screen.getByLabelText(/Password/), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    const submittingButton = await screen.findByRole('button', { name: 'Signing in…' });
    expect(submittingButton).toBeDisabled();
    fireEvent.click(submittingButton);
    expect(mockedAuthApi.login).toHaveBeenCalledTimes(1);
    resolveCurrentUser(user);
    await waitFor(() => expect(mockedAuthApi.getCurrentUser).toHaveBeenCalledTimes(1));
  });
});

describe('authentication context and header', () => {
  it('transitions from unauthenticated to authenticated and logs out in memory', async () => {
    const userEventInstance = userEvent.setup();
    render(
      <MemoryRouter>
        <AuthProvider>
          <Header />
          <AuthStateControl />
        </AuthProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByText('unauthenticated')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Sign in' }).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('link', { name: 'Get Started' }).length).toBeGreaterThan(0);
    await userEventInstance.click(screen.getByRole('button', { name: 'Log in for test' }));
    expect(await screen.findByText('Rail Rider')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    expect(getAccessToken()).toBe('access-token');

    await userEventInstance.click(screen.getByRole('button', { name: 'Log out for test' }));
    expect(await screen.findByText('unauthenticated')).toBeInTheDocument();
    expect(getAccessToken()).toBeNull();
    expect(screen.getAllByRole('link', { name: 'Sign in' }).length).toBeGreaterThan(0);
  });

  it('shows the loading state while restoring an in-memory session', async () => {
    let resolveCurrentUser: (resolvedUser: UserResponse) => void = () => undefined;
    setSession(tokens);
    mockedAuthApi.getCurrentUser.mockImplementationOnce(
      () => new Promise((resolve) => { resolveCurrentUser = resolve; }),
    );
    render(
      <MemoryRouter>
        <AuthProvider>
          <Header />
        </AuthProvider>
      </MemoryRouter>,
    );

    expect(screen.getByRole('status')).toHaveTextContent('Checking account…');
    resolveCurrentUser(user);
    expect(await screen.findByText('Rail Rider')).toBeInTheDocument();
  });
});
