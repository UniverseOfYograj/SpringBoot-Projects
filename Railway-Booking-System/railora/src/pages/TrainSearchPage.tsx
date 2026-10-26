import { useSearchParams, Link } from 'react-router-dom';
import { ArrowLeft, Train, ShieldCheck } from 'lucide-react';
import { ROUTES } from '@/lib/constants';
import { TrainSearchForm } from '@/features/search/components/TrainSearchForm';
import { TrainList } from '@/features/search/components/TrainList';
import { useTrainSearch } from '@/features/search/hooks/useTrainSearch';

export default function TrainSearchPage() {
  const [searchParams, setSearchParams] = useSearchParams();

  const fromParam = (searchParams.get('from') || '').trim().toUpperCase();
  const toParam = (searchParams.get('to') || '').trim().toUpperCase();
  const dateParam = (searchParams.get('date') || '').trim();

  const hasQueryParams = Boolean(fromParam && toParam);

  const {
    data: trains,
    isLoading,
    isError,
    error,
    refetch,
  } = useTrainSearch(fromParam, toParam, {
    enabled: hasQueryParams,
  });

  const handleSearch = (params: { from: string; to: string; date: string }) => {
    const nextParams: Record<string, string> = {
      from: params.from.trim().toUpperCase(),
      to: params.to.trim().toUpperCase(),
    };
    if (params.date) {
      nextParams.date = params.date;
    }
    setSearchParams(nextParams);
  };

  const handleSwapAndSearch = () => {
    if (!fromParam || !toParam) return;
    const nextParams: Record<string, string> = {
      from: toParam,
      to: fromParam,
    };
    if (dateParam) {
      nextParams.date = dateParam;
    }
    setSearchParams(nextParams);
  };

  return (
    <div className="min-h-screen bg-surface-base py-8 sm:py-12">
      <div className="mx-auto max-w-5xl px-4 sm:px-6 lg:px-8 space-y-8">

        {/* ── Page Header & Navigation ── */}
        <div>
          <nav aria-label="Breadcrumb" className="mb-4">
            <Link
              to={ROUTES.HOME}
              className="inline-flex items-center gap-1.5 text-xs font-semibold text-ink-muted hover:text-navy-700
                         focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500 rounded p-0.5
                         transition-interact"
            >
              <ArrowLeft size={14} aria-hidden="true" />
              <span>Back to Overview</span>
            </Link>
          </nav>

          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
            <div>
              <div className="inline-flex items-center gap-2 rounded-full border border-teal-500/20 bg-teal-50 px-3 py-1 text-xs font-semibold text-teal-700 mb-2">
                <Train size={13} className="text-teal-600" aria-hidden="true" />
                <span>Stage 3 — Train Search & Schedule Service</span>
              </div>
              <h1 className="text-2xl sm:text-3xl font-extrabold tracking-tight text-ink-primary">
                Train Schedule Search
              </h1>
              <p className="mt-1 text-sm text-ink-muted">
                Explore scheduled direct routes verified across 283,000+ Indian Railways station stops.
              </p>
            </div>

            <div className="hidden sm:flex items-center gap-2 rounded-xl border border-surface-border bg-surface-card p-3 shadow-2xs">
              <ShieldCheck size={20} className="text-teal-600" aria-hidden="true" />
              <div className="text-2xs">
                <p className="font-semibold text-ink-primary">Live Database Index</p>
                <p className="text-ink-muted">Read-only API integration</p>
              </div>
            </div>
          </div>
        </div>

        {/* ── Search Form Card ── */}
        <section aria-labelledby="search-form-heading">
          <h2 id="search-form-heading" className="sr-only">
            Train Search Filters
          </h2>
          <TrainSearchForm
            initialFrom={fromParam}
            initialTo={toParam}
            initialDate={dateParam}
            isLoading={isLoading}
            onSearch={handleSearch}
          />
        </section>

        {/* ── Search Results or State View ── */}
        <section aria-labelledby="search-results-heading">
          <h2 id="search-results-heading" className="sr-only">
            Search Results
          </h2>
          <TrainList
            trains={trains}
            isLoading={isLoading}
            isError={isError}
            error={error}
            onRetry={() => {
              void refetch();
            }}
            hasSearched={hasQueryParams}
            fromCode={fromParam}
            toCode={toParam}
            travelDate={dateParam}
            onSwapAndSearch={handleSwapAndSearch}
          />
        </section>

      </div>
    </div>
  );
}
