import { Link } from 'react-router-dom';
import { ArrowLeft, Compass } from 'lucide-react';
import { ROUTES } from '@/lib/constants';
import { Button } from '@/components/ui/Button';

/**
 * 404 Not Found page — accessible, on-brand, routes back to home.
 */
export default function NotFoundPage() {
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center px-4 py-20 text-center">
      <div
        className="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-navy-700/5 text-navy-700 border border-navy-700/10"
        aria-hidden="true"
      >
        <Compass size={28} />
      </div>
      <p
        className="text-5xl font-extrabold text-navy-700/25 select-none font-mono"
        aria-hidden="true"
      >
        404
      </p>
      <h1 className="mt-2 text-2xl font-bold tracking-tight text-ink-primary sm:text-3xl">
        Page not found
      </h1>
      <p className="mt-3 max-w-sm text-sm sm:text-base text-ink-muted leading-relaxed">
        The destination you are looking for does not exist on this route or has been rescheduled.
      </p>
      <div className="mt-8">
        <Link to={ROUTES.HOME} tabIndex={-1}>
          <Button variant="primary" size="md" leftIcon={<ArrowLeft size={16} />}>
            Return to Home
          </Button>
        </Link>
      </div>
    </div>
  );
}
