import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Header from '@/components/layout/Header';
import { AuthProvider } from '@/features/auth/AuthContext';

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AuthProvider>{ui}</AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('Header', () => {
  it('renders the Railora wordmark', () => {
    renderWithProviders(<Header />);
    expect(screen.getByRole('banner')).toBeDefined();
    const logoLink = screen.getByRole('link', { name: /railora — go to homepage/i });
    expect(logoLink).toBeDefined();
  });

  it('renders primary navigation links', () => {
    renderWithProviders(<Header />);
    expect(screen.getAllByText('Search Trains').length).toBeGreaterThan(0);
    expect(screen.getAllByText('My Bookings').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Tatkal Queue').length).toBeGreaterThan(0);
  });

  it('has a visible header landmark', () => {
    renderWithProviders(<Header />);
    expect(screen.getByRole('banner')).toBeDefined();
  });
});

describe('utility functions', () => {
  it('cn joins class strings correctly', async () => {
    const { cn } = await import('@/lib/utils');
    expect(cn('a', 'b')).toBe('a b');
    expect(cn('a', false, undefined, 'c')).toBe('a c');
    expect(cn()).toBe('');
  });

  it('formatTime trims to HH:MM', async () => {
    const { formatTime } = await import('@/lib/utils');
    expect(formatTime('08:30:00')).toBe('08:30');
    expect(formatTime(null)).toBe('—');
    expect(formatTime(undefined)).toBe('—');
  });

  it('formatCurrency formats INR correctly', async () => {
    const { formatCurrency } = await import('@/lib/utils');
    expect(formatCurrency(500)).toMatch(/₹|INR/);
    expect(formatCurrency(null)).toBe('—');
  });
});
