import React from 'react';
import { cn } from '@/lib/utils';

export type BadgeVariant = 'navy' | 'teal' | 'neutral' | 'success' | 'warning' | 'danger';
export type BadgeSize = 'sm' | 'md';

export interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  variant?: BadgeVariant;
  size?: BadgeSize;
  dot?: boolean;
}

const variantStyles: Record<BadgeVariant, { container: string; dot: string }> = {
  navy: {
    container: 'bg-navy-50 text-navy-700 border-navy-200',
    dot: 'bg-navy-700',
  },
  teal: {
    container: 'bg-teal-50 text-teal-700 border-teal-200',
    dot: 'bg-teal-600',
  },
  neutral: {
    container: 'bg-surface-muted text-ink-secondary border-surface-border',
    dot: 'bg-ink-muted',
  },
  success: {
    container: 'bg-status-success-bg text-status-success-text border-status-success-border',
    dot: 'bg-emerald-600',
  },
  warning: {
    container: 'bg-status-warning-bg text-status-warning-text border-status-warning-border',
    dot: 'bg-amber-600',
  },
  danger: {
    container: 'bg-status-danger-bg text-status-danger-text border-status-danger-border',
    dot: 'bg-rose-600',
  },
};

const sizeStyles: Record<BadgeSize, string> = {
  sm: 'px-2 py-0.5 text-2xs gap-1',
  md: 'px-2.5 py-1 text-xs gap-1.5',
};

export const Badge: React.FC<BadgeProps> = ({
  variant = 'teal',
  size = 'md',
  dot = false,
  className,
  children,
  ...props
}) => {
  const styles = variantStyles[variant];

  return (
    <span
      className={cn(
        'inline-flex items-center font-medium rounded-full border tracking-wide select-none',
        styles.container,
        sizeStyles[size],
        className,
      )}
      {...props}
    >
      {dot && (
        <span
          className={cn('h-1.5 w-1.5 rounded-full shrink-0', styles.dot)}
          aria-hidden="true"
        />
      )}
      {children}
    </span>
  );
};

export default Badge;
