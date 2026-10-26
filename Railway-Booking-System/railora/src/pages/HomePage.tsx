import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Train,
  Shield,
  Zap,
  ArrowRight,
  Search,
  CheckCircle2,
  Lock,
  Layers,
  Sparkles,
  Database,
} from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Badge } from '@/components/ui/Badge';
import { Card, CardHeader, CardTitle, CardDescription, CardFooter } from '@/components/ui/Card';
import { ROUTES } from '@/lib/constants';

const CORE_PILLARS = [
  {
    icon: Search,
    title: 'High-Performance Search',
    description:
      'Direct station-to-station indexing over 283,000+ Indian Railways stops with sub-second query latency.',
    badge: 'Open Data',
  },
  {
    icon: Zap,
    title: 'Deterministic Seat Holds',
    description:
      'Segment-aware seat allocation with a 5-minute timed hold and automatic background reaper cleanup.',
    badge: 'Atomic Locks',
  },
  {
    icon: Shield,
    title: 'Idempotent Payment Engine',
    description:
      'Database-enforced uniqueness prevents double billing and handles simulated gateway conditions reliably.',
    badge: 'ACID Guarantees',
  },
] as const;

export default function HomePage() {
  const navigate = useNavigate();

  // Demonstration state for Design Foundation showcase (Input focus/validation test)
  const [demoCode, setDemoCode] = useState('');
  const [demoError, setDemoError] = useState('');

  const handleTestValidation = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = e.target.value.toUpperCase();
    setDemoCode(val);
    if (val.length > 5) {
      setDemoError('Station code cannot exceed 5 characters');
    } else {
      setDemoError('');
    }
  };

  return (
    <div className="flex flex-col">

      {/* ── 1. Hero Section ── */}
      <section
        className="relative overflow-hidden bg-navy-700 text-white"
        aria-labelledby="hero-heading"
      >
        {/* Subtle decorative grid background */}
        <div
          className="pointer-events-none absolute inset-0 opacity-[0.035]"
          aria-hidden="true"
          style={{
            backgroundImage:
              'linear-gradient(to right, #fff 1px, transparent 1px), linear-gradient(to bottom, #fff 1px, transparent 1px)',
            backgroundSize: '48px 48px',
          }}
        />

        <div className="relative mx-auto max-w-7xl px-4 py-20 sm:px-6 sm:py-28 lg:px-8 lg:py-32">
          <div className="mx-auto max-w-3xl text-center">

            {/* Pill Tag */}
            <div className="mb-6 inline-flex items-center gap-2 rounded-full border border-teal-400/30 bg-teal-500/10 px-4 py-1.5 text-xs font-semibold text-teal-300">
              <Sparkles size={14} className="text-teal-400" aria-hidden="true" />
              <span>Stage 3 Live — Train Search & Schedule Service</span>
            </div>

            {/* Main Headline */}
            <h1
              id="hero-heading"
              className="text-4xl font-extrabold tracking-tight sm:text-5xl lg:text-6xl text-white"
            >
              Enterprise railway booking,{' '}
              <span className="text-teal-400">engineered for certainty.</span>
            </h1>

            {/* Subheading */}
            <p className="mt-6 text-base sm:text-lg lg:text-xl leading-relaxed text-white/75 font-normal">
              Railora couples a high-integrity Spring Boot 4.1 & PostgreSQL 18 backend with a
              restrained, accessible frontend. Deterministic seat holds, idempotent payments, and clean visual typography.
            </p>

            {/* Hero CTAs */}
            <div className="mt-10 flex flex-col items-center justify-center gap-3.5 sm:flex-row">
              <Button
                variant="accent"
                size="lg"
                onClick={() => navigate(ROUTES.SEARCH)}
                leftIcon={<Search size={18} />}
                className="w-full sm:w-auto shadow-md"
              >
                Search Trains
              </Button>

              <Button
                variant="secondary"
                size="lg"
                disabled
                title="Authentication will activate in Stage 4"
                leftIcon={<Lock size={16} />}
                className="w-full sm:w-auto text-white border-white/20 bg-white/5 hover:bg-white/10 hover:border-white/40 active:bg-white/15"
              >
                Account Portal (Stage 4)
              </Button>
            </div>

            {/* System Trust Metadata */}
            <div className="mt-12 flex flex-wrap items-center justify-center gap-x-8 gap-y-2 text-xs text-white/50">
              <span className="flex items-center gap-1.5">
                <CheckCircle2 size={13} className="text-teal-400" aria-hidden="true" />
                PostgreSQL 18.4 Verified
              </span>
              <span className="flex items-center gap-1.5">
                <CheckCircle2 size={13} className="text-teal-400" aria-hidden="true" />
                Flyway Canonical Schema
              </span>
              <span className="flex items-center gap-1.5">
                <CheckCircle2 size={13} className="text-teal-400" aria-hidden="true" />
                WCAG 2.1 AA Color Contrast
              </span>
            </div>
          </div>
        </div>
      </section>

      {/* ── 2. Core Pillars / Architecture Highlights ── */}
      <section
        className="bg-surface-base py-16 sm:py-24 border-b border-surface-border"
        aria-labelledby="pillars-heading"
      >
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="mb-12 max-w-2xl">
            <Badge variant="teal" size="sm" className="mb-2">Architecture Core</Badge>
            <h2
              id="pillars-heading"
              className="text-2xl font-bold tracking-tight text-ink-primary sm:text-3xl"
            >
              Built upon validated enterprise principles
            </h2>
            <p className="mt-3 text-sm sm:text-base text-ink-muted leading-relaxed">
              Every frontend interaction directly mirrors database-level transactions, eliminating
              ghost bookings, double payments, and race conditions.
            </p>
          </div>

          <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
            {CORE_PILLARS.map(({ icon: Icon, title, description, badge }) => (
              <Card key={title} hoverable className="flex flex-col justify-between">
                <CardHeader>
                  <div className="flex items-center justify-between mb-3">
                    <div
                      className="flex h-10 w-10 items-center justify-center rounded-lg bg-navy-700/5 text-navy-700 border border-navy-700/10"
                      aria-hidden="true"
                    >
                      <Icon size={20} />
                    </div>
                    <Badge variant="neutral" size="sm">{badge}</Badge>
                  </div>
                  <CardTitle>{title}</CardTitle>
                  <CardDescription>{description}</CardDescription>
                </CardHeader>
                <CardFooter className="text-xs text-ink-muted">
                  <span>Standardized in Stage 0 Audit</span>
                  <ArrowRight size={14} className="text-ink-disabled" aria-hidden="true" />
                </CardFooter>
              </Card>
            ))}
          </div>
        </div>
      </section>

      {/* ── 3. Design System & Component Foundation Showcase ── */}
      <section
        className="bg-white py-16 sm:py-20"
        aria-labelledby="design-system-heading"
      >
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="mb-12 text-center max-w-2xl mx-auto">
            <Badge variant="navy" size="sm" className="mb-2">Stage 2 Deliverables</Badge>
            <h2
              id="design-system-heading"
              className="text-2xl font-bold tracking-tight text-ink-primary sm:text-3xl"
            >
              Design Tokens & Reusable UI Primitives
            </h2>
            <p className="mt-3 text-sm sm:text-base text-ink-muted">
              Live inspection gallery of the foundational design tokens, inputs, badges, and button states.
            </p>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">

            {/* Token Palette Card */}
            <Card className="p-6">
              <div className="flex items-center gap-2 mb-4">
                <Layers size={18} className="text-teal-600" aria-hidden="true" />
                <h3 className="text-base font-bold text-ink-primary">Color Tokens</h3>
              </div>
              <div className="space-y-3">
                <div className="flex items-center justify-between p-2.5 rounded-lg bg-navy-700 text-white">
                  <span className="text-xs font-semibold">Primary Navy</span>
                  <span className="text-2xs font-mono opacity-80">#142B49</span>
                </div>
                <div className="flex items-center justify-between p-2.5 rounded-lg bg-teal-500 text-white">
                  <span className="text-xs font-semibold">Teal Accent</span>
                  <span className="text-2xs font-mono opacity-80">#0F766E</span>
                </div>
                <div className="flex items-center justify-between p-2.5 rounded-lg bg-surface-base border border-surface-border text-ink-primary">
                  <span className="text-xs font-semibold">Warm Surface</span>
                  <span className="text-2xs font-mono text-ink-muted">#FAFAF9</span>
                </div>
                <div className="flex items-center justify-between p-2.5 rounded-lg bg-surface-muted border border-surface-border text-ink-secondary">
                  <span className="text-xs font-semibold">Slate Divider</span>
                  <span className="text-2xs font-mono text-ink-muted">#F1F5F9</span>
                </div>
              </div>
            </Card>

            {/* Input & Form Preview Card */}
            <Card className="p-6">
              <div className="flex items-center gap-2 mb-4">
                <Search size={18} className="text-navy-700" aria-hidden="true" />
                <h3 className="text-base font-bold text-ink-primary">Accessible Inputs</h3>
              </div>
              <div className="space-y-4">
                <Input
                  label="Station Code"
                  placeholder="e.g. NDLS or HWH"
                  value={demoCode}
                  onChange={handleTestValidation}
                  error={demoError}
                  helperText="Valid uppercase IR code"
                  leftIcon={<Train size={15} />}
                  required
                />
                <div className="pt-1 flex items-center justify-between text-2xs text-ink-muted">
                  <span>Semantic labels with ARIA error bindings</span>
                  <Badge variant="teal" size="sm">WCAG Compliant</Badge>
                </div>
              </div>
            </Card>

            {/* Badges & Button Variants Card */}
            <Card className="p-6">
              <div className="flex items-center gap-2 mb-4">
                <Database size={18} className="text-teal-600" aria-hidden="true" />
                <h3 className="text-base font-bold text-ink-primary">Component States</h3>
              </div>
              <div className="space-y-4">
                <div>
                  <p className="text-xs font-semibold uppercase text-ink-muted tracking-wider mb-2">Badge Variants</p>
                  <div className="flex flex-wrap gap-1.5">
                    <Badge variant="navy" dot>Booked</Badge>
                    <Badge variant="teal" dot>Available</Badge>
                    <Badge variant="warning" dot>On Hold</Badge>
                    <Badge variant="danger" dot>Expired</Badge>
                    <Badge variant="neutral">General</Badge>
                  </div>
                </div>

                <div className="pt-2 border-t border-surface-border">
                  <p className="text-xs font-semibold uppercase text-ink-muted tracking-wider mb-2">Button Sizes</p>
                  <div className="flex flex-wrap items-center gap-2">
                    <Button variant="primary" size="sm">Small</Button>
                    <Button variant="accent" size="md">Medium</Button>
                    <Button variant="secondary" size="sm">Secondary</Button>
                  </div>
                </div>
              </div>
            </Card>
          </div>
        </div>
      </section>

      {/* ── 4. Readiness & Next Milestone Banner ── */}
      <section
        className="bg-surface-base border-t border-surface-border py-10"
        aria-label="Current implementation stage"
      >
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="rounded-xl border border-teal-500/30 bg-teal-50/60 p-6 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
            <div className="space-y-1">
              <div className="flex items-center gap-2">
                <Badge variant="teal" size="sm">Stage 3 Active</Badge>
                <h3 className="text-sm font-bold text-teal-900">
                  Train Timetable Search & Read-Only API Integration
                </h3>
              </div>
              <p className="text-xs sm:text-sm text-teal-800/80 leading-relaxed max-w-2xl">
                Explore live scheduled train routes directly backed by Spring Boot and PostgreSQL.
                Ready for user testing before Stage 4 Authentication & User Management.
              </p>
            </div>
            <div className="shrink-0">
              <Button
                variant="accent"
                size="sm"
                onClick={() => navigate(ROUTES.SEARCH)}
                leftIcon={<Search size={14} />}
              >
                Go to Search
              </Button>
            </div>
          </div>
        </div>
      </section>

    </div>
  );
}
