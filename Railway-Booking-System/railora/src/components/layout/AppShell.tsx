import type { ReactNode } from 'react';
import Header from '@/components/layout/Header';
import Footer from '@/components/layout/Footer';

interface AppShellProps {
  children: ReactNode;
}

/**
 * AppShell wraps every page with the global Header, Footer,
 * and an accessible Skip-to-Content anchor for screen-reader and keyboard users.
 */
export default function AppShell({ children }: AppShellProps) {
  return (
    <div className="flex min-h-screen flex-col bg-surface-base text-ink-primary antialiased selection:bg-teal-100 selection:text-teal-900">
      {/* ── Accessible skip link ── */}
      <a href="#main-content" className="skip-link">
        Skip to main content
      </a>

      {/* ── Global Header ── */}
      <Header />

      {/* ── Main content area ── */}
      <main
        id="main-content"
        className="flex-1 focus:outline-none"
        tabIndex={-1}
        role="main"
      >
        {children}
      </main>

      {/* ── Global Footer ── */}
      <Footer />
    </div>
  );
}
