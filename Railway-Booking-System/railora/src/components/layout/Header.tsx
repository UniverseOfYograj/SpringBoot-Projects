import { useState, useEffect } from 'react';
import { Link, NavLink, useLocation } from 'react-router-dom';
import { Train, Menu, X, ArrowRight, ShieldCheck } from 'lucide-react';
import { ROUTES } from '@/lib/constants';

const NAV_LINKS = [
  { label: 'Search Trains', href: ROUTES.SEARCH },
  { label: 'My Bookings', href: ROUTES.BOOKINGS },
] as const;

/**
 * Railora global header.
 *
 * Enterprise-grade presentation:
 * - Brand wordmark with custom train badge and system indicator
 * - Restrained desktop navigation with keyboard-accessible focus rings
 * - Smooth, accessible mobile drawer with Escape-key dismiss
 */
export default function Header() {
  const [mobileOpen, setMobileOpen] = useState(false);
  const location = useLocation();

  // Close mobile drawer on route change
  useEffect(() => {
    setMobileOpen(false);
  }, [location.pathname]);

  // Handle ESC key to dismiss mobile drawer
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && mobileOpen) {
        setMobileOpen(false);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [mobileOpen]);

  // Lock body scroll when mobile drawer is open
  useEffect(() => {
    if (mobileOpen) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
    }
    return () => {
      document.body.style.overflow = '';
    };
  }, [mobileOpen]);

  return (
    <header
      className="sticky top-0 z-50 border-b border-white/10 bg-navy-700/98 backdrop-blur-md shadow-md"
      role="banner"
    >
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="flex h-16 items-center justify-between">

          {/* ── Brand wordmark & system badge ── */}
          <div className="flex items-center gap-4">
            <Link
              to={ROUTES.HOME}
              className="group flex items-center gap-2.5 rounded-lg p-1 -ml-1
                         focus-visible:outline-none focus-visible:ring-2
                         focus-visible:ring-teal-400 focus-visible:ring-offset-2
                         focus-visible:ring-offset-navy-700"
              aria-label="Railora — Go to homepage"
            >
              <div
                className="flex h-9 w-9 items-center justify-center rounded-lg
                           bg-teal-500 shadow-sm transition-interact group-hover:bg-teal-400"
                aria-hidden="true"
              >
                <Train size={18} strokeWidth={2.2} className="text-white" />
              </div>
              <div className="flex flex-col">
                <span className="text-xl font-bold tracking-tight text-white select-none leading-none">
                  Rail<span className="text-teal-400">ora</span>
                </span>
                <span className="text-2xs font-medium tracking-wider text-white/50 uppercase mt-0.5">
                  Railway Booking
                </span>
              </div>
            </Link>

            <span className="hidden lg:inline-flex items-center gap-1 rounded-full bg-white/5 border border-white/10 px-2.5 py-0.5 text-2xs font-medium text-white/70">
              <ShieldCheck size={11} className="text-teal-400" aria-hidden="true" />
              Verified API Backend
            </span>
          </div>

          {/* ── Desktop primary navigation ── */}
          <nav
            className="hidden md:flex md:items-center md:gap-1.5"
            aria-label="Primary navigation"
          >
            {NAV_LINKS.map(({ label, href }) => (
              <NavLink
                key={href}
                to={href}
                className={({ isActive }) =>
                  [
                    'px-3.5 py-2 rounded-md text-sm font-medium transition-interact',
                    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-400 focus-visible:ring-offset-2 focus-visible:ring-offset-navy-700',
                    isActive
                      ? 'text-white bg-white/15 font-semibold shadow-inner'
                      : 'text-white/80 hover:text-white hover:bg-white/10',
                  ].join(' ')
                }
              >
                {label}
              </NavLink>
            ))}
          </nav>

          {/* ── Desktop auth actions (placeholder for Stage 4) ── */}
          <div className="hidden md:flex md:items-center md:gap-3" aria-label="Account actions">
            <button
              type="button"
              className="px-3 py-1.5 rounded-md text-sm font-medium text-white/80
                         hover:text-white hover:bg-white/10 transition-interact
                         focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-400 focus-visible:ring-offset-2 focus-visible:ring-offset-navy-700
                         disabled:opacity-50 disabled:cursor-not-allowed"
              disabled
              title="Authentication arrives in Stage 4"
              aria-label="Log in to Railora (available in Stage 4)"
            >
              Sign in
            </button>
            <button
              type="button"
              className="inline-flex items-center justify-center gap-1.5 rounded-lg
                         px-4 py-2 text-sm font-semibold
                         bg-teal-500 text-white shadow-sm
                         hover:bg-teal-400 active:bg-teal-600
                         transition-interact
                         focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-offset-2 focus-visible:ring-offset-navy-700
                         disabled:opacity-50 disabled:cursor-not-allowed"
              disabled
              title="Authentication arrives in Stage 4"
              aria-label="Create account (available in Stage 4)"
            >
              <span>Get Started</span>
              <ArrowRight size={14} aria-hidden="true" />
            </button>
          </div>

          {/* ── Mobile menu toggle button ── */}
          <button
            type="button"
            className="p-2 rounded-lg text-white/80 hover:text-white hover:bg-white/10
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-400 focus-visible:ring-offset-2 focus-visible:ring-offset-navy-700
                       md:hidden transition-interact"
            aria-label={mobileOpen ? 'Close navigation menu' : 'Open navigation menu'}
            aria-expanded={mobileOpen}
            aria-controls="mobile-nav-panel"
            onClick={() => setMobileOpen((prev) => !prev)}
          >
            {mobileOpen ? (
              <X size={22} aria-hidden="true" />
            ) : (
              <Menu size={22} aria-hidden="true" />
            )}
          </button>
        </div>
      </div>

      {/* ── Mobile navigation drawer & backdrop ── */}
      {mobileOpen && (
        <>
          <div
            className="fixed inset-0 top-16 bg-navy-950/60 backdrop-blur-sm z-40 md:hidden"
            aria-hidden="true"
            onClick={() => setMobileOpen(false)}
          />
          <nav
            id="mobile-nav-panel"
            className="relative z-50 border-t border-white/10 bg-navy-800 md:hidden shadow-xl"
            aria-label="Mobile navigation"
          >
            <div className="mx-auto max-w-7xl px-4 py-4 space-y-2 sm:px-6">
              <div className="space-y-1">
                {NAV_LINKS.map(({ label, href }) => (
                  <NavLink
                    key={href}
                    to={href}
                    onClick={() => setMobileOpen(false)}
                    className={({ isActive }) =>
                      [
                        'flex items-center justify-between rounded-lg px-3.5 py-2.5 text-sm font-medium transition-interact',
                        isActive
                          ? 'bg-white/15 text-white font-semibold'
                          : 'text-white/80 hover:bg-white/10 hover:text-white',
                      ].join(' ')
                    }
                  >
                    <span>{label}</span>
                    <ArrowRight size={14} className="text-white/40" aria-hidden="true" />
                  </NavLink>
                ))}
              </div>

              <div className="pt-3 border-t border-white/10 flex flex-col gap-2">
                <button
                  type="button"
                  className="w-full py-2.5 px-3 rounded-lg text-sm font-medium text-white/80 bg-white/5 hover:bg-white/10 text-left transition-interact"
                  disabled
                >
                  Sign in (Stage 4)
                </button>
                <button
                  type="button"
                  className="w-full inline-flex items-center justify-center gap-2 rounded-lg
                             py-2.5 px-3 text-sm font-semibold
                             bg-teal-500 text-white shadow-sm
                             hover:bg-teal-400 active:bg-teal-600
                             transition-interact disabled:opacity-50"
                  disabled
                >
                  Get Started (Stage 4)
                </button>
              </div>
            </div>
          </nav>
        </>
      )}
    </header>
  );
}
