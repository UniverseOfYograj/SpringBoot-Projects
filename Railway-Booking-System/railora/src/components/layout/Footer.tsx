import { Link } from 'react-router-dom';
import { Train, Github, Shield, Lock, CheckCircle2 } from 'lucide-react';
import { ROUTES } from '@/lib/constants';

const FOOTER_SECTIONS = [
  {
    heading: 'Platform',
    links: [
      { label: 'Train Search', href: ROUTES.SEARCH },
      { label: 'Booking History', href: ROUTES.BOOKINGS },
      { label: 'API Health Status', href: '#' },
    ],
  },
  {
    heading: 'Architecture',
    links: [
      { label: 'Spring Boot 4.1 Backend', href: '#' },
      { label: 'PostgreSQL 18 Storage', href: '#' },
      { label: 'Flyway Baseline Migrations', href: '#' },
    ],
  },
  {
    heading: 'Security & Safety',
    links: [
      { label: 'Idempotent Payment Engine', href: '#' },
      { label: 'Atomic Seat Hold Reaper', href: '#' },
      { label: 'JWT Stateless Auth', href: '#' },
    ],
  },
] as const;

const TRUST_BADGES = [
  { icon: Shield, title: 'Safe DB Isolation', desc: 'Zero data leakage guarantee' },
  { icon: Lock, title: 'BCrypt & JWT Security', desc: 'Stateless session integrity' },
  { icon: CheckCircle2, title: 'Flyway Versioned', desc: 'Repeatable canonical schema' },
] as const;

const CURRENT_YEAR = new Date().getFullYear();

/**
 * Railora global enterprise footer.
 */
export default function Footer() {
  return (
    <footer
      className="border-t border-white/10 bg-navy-800 text-white"
      role="contentinfo"
      aria-label="Railora platform footer"
    >
      {/* ── Trust highlights strip ── */}
      <div className="border-b border-white/10 bg-navy-900/40">
        <div className="mx-auto max-w-7xl px-4 py-6 sm:px-6 lg:px-8">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            {TRUST_BADGES.map(({ icon: Icon, title, desc }) => (
              <div key={title} className="flex items-center gap-3">
                <div
                  className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-teal-500/10 border border-teal-500/20 text-teal-400"
                  aria-hidden="true"
                >
                  <Icon size={18} />
                </div>
                <div>
                  <p className="text-xs font-semibold tracking-wide text-white">{title}</p>
                  <p className="text-2xs text-white/50">{desc}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* ── Main footer navigation ── */}
      <div className="mx-auto max-w-7xl px-4 py-12 sm:px-6 lg:px-8">
        <div className="grid grid-cols-1 gap-10 lg:grid-cols-5">

          {/* ── Brand summary column ── */}
          <div className="lg:col-span-2 space-y-4">
            <Link
              to={ROUTES.HOME}
              className="inline-flex items-center gap-2.5 rounded-lg p-1 -ml-1
                         focus-visible:outline-none focus-visible:ring-2
                         focus-visible:ring-teal-400 focus-visible:ring-offset-2
                         focus-visible:ring-offset-navy-800"
              aria-label="Railora — Return to homepage"
            >
              <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-teal-500 shadow-sm" aria-hidden="true">
                <Train size={16} strokeWidth={2.2} className="text-white" />
              </div>
              <span className="text-xl font-bold tracking-tight text-white">
                Rail<span className="text-teal-400">ora</span>
              </span>
            </Link>
            <p className="max-w-sm text-xs sm:text-sm leading-relaxed text-white/60">
              Enterprise-grade railway booking application with deterministic seat holds,
              verified Flyway migrations, and robust PostgreSQL transactional guarantees.
            </p>
            <div className="pt-2">
              <span className="inline-flex items-center gap-1.5 rounded-full bg-white/5 border border-white/10 px-3 py-1 text-2xs text-white/70">
                <span className="h-2 w-2 rounded-full bg-emerald-400" aria-hidden="true" />
                Backend Service Operational (Port 8080)
              </span>
            </div>
          </div>

          {/* ── Links grid ── */}
          <div className="grid grid-cols-2 gap-8 sm:grid-cols-3 lg:col-span-3">
            {FOOTER_SECTIONS.map(({ heading, links }) => (
              <div key={heading} className="space-y-3">
                <h3 className="text-xs font-bold uppercase tracking-widest text-teal-400 select-none">
                  {heading}
                </h3>
                <ul className="space-y-2" role="list">
                  {links.map(({ label, href }) => (
                    <li key={label}>
                      <Link
                        to={href}
                        className="text-xs sm:text-sm text-white/70 transition-interact
                                   hover:text-white hover:underline hover:underline-offset-4
                                   focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-teal-400 rounded"
                      >
                        {label}
                      </Link>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </div>

        {/* ── Bottom copyright and attribution bar ── */}
        <div className="mt-12 flex flex-col items-center justify-between gap-4 border-t border-white/10 pt-8 text-xs text-white/40 sm:flex-row">
          <span>© {CURRENT_YEAR} Railora. All rights reserved. Indian Railways Open Data.</span>
          <div className="flex items-center gap-6">
            <a
              href="https://github.com/UniverseOfYograj/SpringBoot-Projects"
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1.5 transition-interact hover:text-white
                         focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-teal-400 rounded px-1"
              aria-label="View source repository on GitHub (opens in new tab)"
            >
              <Github size={14} aria-hidden="true" />
              <span>GitHub Repository</span>
            </a>
          </div>
        </div>
      </div>
    </footer>
  );
}
