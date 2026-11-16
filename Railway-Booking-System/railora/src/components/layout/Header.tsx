import { useState, useEffect } from 'react';
import { Link, NavLink, useLocation } from 'react-router-dom';
import { Train, Menu, X, ArrowRight, Zap, Activity } from 'lucide-react';
import { ROUTES } from '@/lib/constants';
import { useAuth } from '@/features/auth/AuthContext';

const NAV_LINKS = [
  { label: 'Search Trains', href: ROUTES.SEARCH },
  { label: 'My Bookings', href: ROUTES.BOOKINGS },
  { label: 'PNR Status', href: ROUTES.PNR },
  { label: 'Tatkal Queue', href: ROUTES.WAITING_ROOM },
] as const;

/**
 * Railora Maglev / Shinkansen Aerodynamic Header.
 *
 * Futuristic high-speed train aesthetic:
 * - Bullet train aerodynamic cockpit branding with motion trail
 * - Live Maglev speed & concurrency telemetry badge
 * - Magnetic levitation active nav pill with neon plasma glow
 * - High-speed speed-rail magnetic beam track border
 * - Smooth accessible mobile drawer with Escape-key dismiss
 */
export default function Header() {
  const [mobileOpen, setMobileOpen] = useState(false);
  const [currentTime, setCurrentTime] = useState<string>('');
  const location = useLocation();
  const { state: authState, logout } = useAuth();

  // Dynamic live clock for bullet train telemetry
  useEffect(() => {
    const updateClock = () => {
      const now = new Date();
      setCurrentTime(
        now.toLocaleTimeString('en-IN', {
          hour: '2-digit',
          minute: '2-digit',
          second: '2-digit',
          hour12: false,
        }),
      );
    };
    updateClock();
    const interval = setInterval(updateClock, 1000);
    return () => clearInterval(interval);
  }, []);

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
      className="sticky top-0 z-50 border-b border-teal-500/20 bg-navy-700 shadow-[0_4px_30px_rgba(0,0,0,0.7)] backdrop-blur-xl relative overflow-hidden"
      role="banner"
    >
      {/* High-speed magnetic rail glow track along top and bottom */}
      <div className="absolute top-0 left-0 right-0 h-[1px] bg-gradient-to-r from-transparent via-teal-400/50 to-transparent" />
      <div className="absolute bottom-0 left-0 right-0 h-[1.5px] bg-gradient-to-r from-transparent via-teal-400 to-transparent opacity-90 animate-pulse" />

      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="flex h-16 items-center justify-between gap-4">

          {/* ── Brand wordmark & Bullet Train cockpit badge ── */}
          <div className="flex items-center gap-3.5">
            <Link
              to={ROUTES.HOME}
              className="group flex items-center gap-3 rounded-xl p-1 -ml-1
                         focus-visible:outline-none focus-visible:ring-2
                         focus-visible:ring-teal-400 focus-visible:ring-offset-2
                         focus-visible:ring-offset-slate-950 transition-transform hover:scale-[1.02]"
              aria-label="Railora — Go to homepage"
            >
              {/* Aerodynamic Locomotive Nose Icon */}
              <div
                className="relative flex h-10 w-10 items-center justify-center rounded-xl
                           bg-gradient-to-br from-teal-400 via-teal-600 to-navy-900 p-0.5 shadow-[0_0_15px_rgba(20,184,166,0.4)]
                           border border-teal-300/40 transition-interact group-hover:shadow-[0_0_22px_rgba(45,212,191,0.6)]"
                aria-hidden="true"
              >
                <div className="flex h-full w-full items-center justify-center rounded-[10px] bg-slate-950/70">
                  <Train size={20} strokeWidth={2.4} className="text-teal-400 group-hover:text-teal-300 transition-colors" />
                </div>
                {/* Wind trail speed blip */}
                <span className="absolute -top-1 -right-1 flex h-2.5 w-2.5">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-teal-400 opacity-75" />
                  <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-teal-500" />
                </span>
              </div>

              <div className="flex flex-col">
                <div className="flex items-center gap-1.5">
                  <span className="text-xl font-black tracking-tight text-white select-none leading-none">
                    Rail<span className="text-transparent bg-clip-text bg-gradient-to-r from-teal-400 to-emerald-300">ora</span>
                  </span>
                  <span className="hidden sm:inline-block rounded px-1.5 py-0.2 text-[9px] font-extrabold uppercase tracking-widest bg-teal-500/20 text-teal-300 border border-teal-500/30">
                    MAGLEV
                  </span>
                </div>
                <span className="text-[10px] font-semibold tracking-wider text-teal-200/60 uppercase mt-0.5">
                  Ultra-Fast Railway Engine
                </span>
              </div>
            </Link>

            {/* Maglev Live Speed / Concurrency Telemetry Pill */}
            <div className="hidden xl:flex items-center gap-2 rounded-full bg-white/5 border border-white/10 px-3 py-1 text-2xs font-mono text-slate-300">
              <span className="flex h-2 w-2 rounded-full bg-emerald-400 shadow-[0_0_8px_#34d399] animate-pulse" />
              <span className="text-emerald-400 font-bold">LIVE</span>
              <span className="text-slate-500">•</span>
              <span className="text-teal-300 flex items-center gap-1">
                <Zap size={11} className="text-teal-400" />
                350 km/h Maglev
              </span>
              <span className="text-slate-500">•</span>
              <span className="text-slate-400 flex items-center gap-1">
                <Activity size={11} className="text-emerald-400" />
                {currentTime || 'IST Live'}
              </span>
            </div>
          </div>

          {/* ── Desktop primary navigation (Floating Magnetic Pods) ── */}
          <nav
            className="hidden md:flex md:items-center md:gap-1.5 p-1 rounded-2xl bg-white/[0.04] border border-white/[0.08] backdrop-blur-md"
            aria-label="Primary navigation"
          >
            {NAV_LINKS.map(({ label, href }) => (
              <NavLink
                key={href}
                to={href}
                className={({ isActive }) => {
                  const isPnrActive =
                    href === ROUTES.PNR &&
                    (isActive || (location.pathname === ROUTES.BOOKINGS && location.search.includes('tab=pnr')));
                  const isBookingsActive =
                    href === ROUTES.BOOKINGS &&
                    isActive &&
                    !location.search.includes('tab=pnr');
                  const active = href === ROUTES.PNR ? isPnrActive : href === ROUTES.BOOKINGS ? isBookingsActive : isActive;
                  return [
                    'px-3.5 py-1.5 rounded-xl text-xs font-semibold transition-all duration-200',
                    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-400 focus-visible:ring-offset-2 focus-visible:ring-offset-slate-950',
                    active
                      ? 'bg-white/20 text-white text-teal-200 bg-gradient-to-r from-teal-500/25 to-teal-600/15 border border-teal-500/40 shadow-[0_0_15px_rgba(20,184,166,0.35)] font-bold'
                      : 'text-slate-300 hover:text-white hover:bg-white/[0.08] border border-transparent',
                  ].join(' ');
                }}
              >
                {label}
              </NavLink>
            ))}
          </nav>

          {/* ── User Authentication / Actions ── */}
          <div className="hidden md:flex md:items-center md:gap-3" aria-label="Account actions">
            {authState.status === 'authenticated' ? (
              <div className="flex items-center gap-3 bg-white/[0.05] border border-white/10 rounded-xl px-3 py-1">
                <div className="flex items-center gap-2">
                  <div className="h-6 w-6 rounded-full bg-teal-500/20 border border-teal-400/40 flex items-center justify-center text-teal-300 text-xs font-bold font-mono">
                    {authState.user.fullName.charAt(0).toUpperCase()}
                  </div>
                  <span className="max-w-36 truncate text-xs font-semibold text-slate-200">
                    {authState.user.fullName}
                  </span>
                </div>
                <button
                  type="button"
                  onClick={logout}
                  className="rounded-lg px-2.5 py-1 text-xs font-medium text-slate-400 transition-interact hover:bg-red-500/20 hover:text-red-300 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-400"
                >
                  Sign out
                </button>
              </div>
            ) : authState.status === 'unauthenticated' ? (
              <div className="flex items-center gap-2">
                <Link
                  to={ROUTES.LOGIN}
                  className="rounded-xl px-3.5 py-2 text-xs font-semibold text-slate-300 transition-interact hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-400"
                >
                  Sign in
                </Link>
                <Link
                  to={ROUTES.REGISTER}
                  className="inline-flex items-center justify-center gap-1.5 rounded-xl bg-gradient-to-r from-teal-500 to-emerald-400 px-4 py-2 text-xs font-bold text-slate-950 shadow-[0_0_15px_rgba(20,184,166,0.4)] transition-all hover:brightness-110 hover:shadow-[0_0_22px_rgba(45,212,191,0.6)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-400"
                >
                  <span>Get Started</span>
                  <ArrowRight size={13} strokeWidth={2.5} aria-hidden="true" />
                </Link>
              </div>
            ) : (
              <span role="status" className="text-xs font-mono text-slate-400">Verifying session…</span>
            )}
          </div>

          {/* ── Mobile menu toggle button ── */}
          <button
            type="button"
            className="p-2 rounded-xl text-slate-300 hover:text-white hover:bg-white/10
                       border border-white/10
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-400 focus-visible:ring-offset-2 focus-visible:ring-offset-slate-950
                       md:hidden transition-interact"
            aria-label={mobileOpen ? 'Close navigation menu' : 'Open navigation menu'}
            aria-expanded={mobileOpen}
            aria-controls="mobile-nav-panel"
            onClick={() => setMobileOpen((prev) => !prev)}
          >
            {mobileOpen ? (
              <X size={20} aria-hidden="true" />
            ) : (
              <Menu size={20} aria-hidden="true" />
            )}
          </button>
        </div>
      </div>

      {/* ── Mobile navigation drawer & backdrop ── */}
      {mobileOpen && (
        <>
          <div
            className="fixed inset-0 top-16 bg-slate-950/80 backdrop-blur-md z-40 md:hidden"
            aria-hidden="true"
            onClick={() => setMobileOpen(false)}
          />
          <nav
            id="mobile-nav-panel"
            className="relative z-50 border-t border-teal-500/20 bg-slate-950 md:hidden shadow-2xl"
            aria-label="Mobile navigation"
          >
            <div className="mx-auto max-w-7xl px-4 py-4 space-y-2 sm:px-6">
              <div className="space-y-1">
                {NAV_LINKS.map(({ label, href }) => (
                  <NavLink
                    key={href}
                    to={href}
                    onClick={() => setMobileOpen(false)}
                    className={({ isActive }) => {
                      const isPnrActive =
                        href === ROUTES.PNR &&
                        (isActive || (location.pathname === ROUTES.BOOKINGS && location.search.includes('tab=pnr')));
                      const isBookingsActive =
                        href === ROUTES.BOOKINGS &&
                        isActive &&
                        !location.search.includes('tab=pnr');
                      const active = href === ROUTES.PNR ? isPnrActive : href === ROUTES.BOOKINGS ? isBookingsActive : isActive;
                      return [
                        'flex items-center justify-between rounded-xl px-4 py-3 text-sm font-semibold transition-interact',
                        active
                          ? 'bg-gradient-to-r from-teal-500/20 to-teal-600/10 text-teal-300 border border-teal-500/30'
                          : 'text-slate-300 hover:bg-white/5 hover:text-white',
                      ].join(' ');
                    }}
                  >
                    <span>{label}</span>
                    <ArrowRight size={14} className="text-teal-400" aria-hidden="true" />
                  </NavLink>
                ))}
              </div>

              <div className="pt-3 border-t border-white/10 flex flex-col gap-2">
                {authState.status === 'authenticated' ? (
                  <>
                    <p className="px-4 py-2 text-xs font-semibold text-slate-400">
                      Signed in as <span className="text-white font-bold">{authState.user.fullName}</span>
                    </p>
                    <button
                      type="button"
                      onClick={() => {
                        logout();
                        setMobileOpen(false);
                      }}
                      className="w-full rounded-xl bg-red-500/10 border border-red-500/20 px-4 py-2.5 text-left text-xs font-bold text-red-400 transition-interact hover:bg-red-500/20"
                    >
                      Sign out
                    </button>
                  </>
                ) : authState.status === 'unauthenticated' ? (
                  <>
                    <Link
                      to={ROUTES.LOGIN}
                      onClick={() => setMobileOpen(false)}
                      className="w-full rounded-xl bg-white/5 border border-white/10 px-4 py-2.5 text-center text-xs font-semibold text-slate-200 transition-interact hover:bg-white/10"
                    >
                      Sign in
                    </Link>
                    <Link
                      to={ROUTES.REGISTER}
                      onClick={() => setMobileOpen(false)}
                      className="inline-flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-teal-500 to-emerald-400 px-4 py-2.5 text-xs font-bold text-slate-950 shadow-md transition-interact hover:brightness-110"
                    >
                      Get Started
                      <ArrowRight size={14} strokeWidth={2.5} aria-hidden="true" />
                    </Link>
                  </>
                ) : (
                  <span role="status" className="px-4 py-2 text-xs text-slate-400">
                    Checking account…
                  </span>
                )}
              </div>
            </div>
          </nav>
        </>
      )}
    </header>
  );
}
