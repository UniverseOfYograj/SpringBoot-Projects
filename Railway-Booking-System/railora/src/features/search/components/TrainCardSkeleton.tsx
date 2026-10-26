import { Skeleton } from '@/components/ui/Skeleton';

export function TrainCardSkeleton() {
  return (
    <div
      aria-hidden="true"
      className="rounded-xl border border-surface-border bg-surface-card p-5 sm:p-6 shadow-xs"
    >
      {/* Header Skeleton */}
      <div className="flex items-center justify-between border-b border-surface-border/60 pb-4">
        <div className="flex items-center gap-3">
          <Skeleton width={64} height={24} rounded="md" />
          <Skeleton width={180} height={24} rounded="md" />
        </div>
        <Skeleton width={90} height={20} rounded="full" />
      </div>

      {/* Body Skeleton */}
      <div className="mt-5 grid grid-cols-1 gap-6 sm:grid-cols-12 sm:items-center">
        <div className="sm:col-span-4 space-y-2">
          <Skeleton width={100} height={32} rounded="md" />
          <Skeleton width={160} height={18} rounded="md" />
        </div>

        <div className="sm:col-span-4 flex flex-col items-center justify-center space-y-2">
          <Skeleton width={70} height={16} rounded="md" />
          <Skeleton width={140} height={8} rounded="full" />
          <Skeleton width={80} height={14} rounded="md" />
        </div>

        <div className="sm:col-span-4 flex flex-col sm:items-end space-y-2">
          <Skeleton width={100} height={32} rounded="md" />
          <Skeleton width={160} height={18} rounded="md" />
        </div>
      </div>

      {/* Footer Skeleton */}
      <div className="mt-5 flex items-center justify-between border-t border-surface-border/50 pt-4">
        <Skeleton width={140} height={16} rounded="md" />
        <Skeleton width={110} height={28} rounded="md" />
      </div>
    </div>
  );
}
