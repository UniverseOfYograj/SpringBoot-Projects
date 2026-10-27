import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import AppShell from '@/components/layout/AppShell';
import AppRouter from '@/app/router';
import { AuthProvider } from '@/features/auth/AuthContext';

/**
 * Global QueryClient — configured for enterprise-grade reliability:
 * - 2-minute stale time prevents unnecessary refetches for stable data.
 * - 1 retry for network errors; retry on server error 5xx.
 * - Background refetch on window focus disabled for predictable UX.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 2 * 60 * 1000,
      retry: 1,
      retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 10000),
      refetchOnWindowFocus: false,
    },
    mutations: {
      retry: 0,
    },
  },
});

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <AppShell>
            <AppRouter />
          </AppShell>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
