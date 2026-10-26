import React, { forwardRef } from 'react';
import { Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';

export type ButtonVariant = 'primary' | 'accent' | 'secondary' | 'ghost' | 'danger';
export type ButtonSize = 'sm' | 'md' | 'lg';

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  isLoading?: boolean;
  leftIcon?: React.ReactNode;
  rightIcon?: React.ReactNode;
  fullWidth?: boolean;
}

const variantStyles: Record<ButtonVariant, string> = {
  primary:
    'bg-navy-700 text-white hover:bg-navy-800 active:bg-navy-900 border border-transparent shadow-sm focus-visible:ring-2 focus-visible:ring-teal-500 focus-visible:ring-offset-2',
  accent:
    'bg-teal-500 text-white hover:bg-teal-600 active:bg-teal-700 border border-transparent shadow-sm focus-visible:ring-2 focus-visible:ring-teal-500 focus-visible:ring-offset-2',
  secondary:
    'bg-surface-card text-ink-primary border border-surface-border hover:bg-surface-muted hover:border-surface-subtle active:bg-slate-100 shadow-sm focus-visible:ring-2 focus-visible:ring-teal-500 focus-visible:ring-offset-2',
  ghost:
    'bg-transparent text-ink-secondary hover:text-ink-primary hover:bg-surface-muted active:bg-slate-200 border border-transparent focus-visible:ring-2 focus-visible:ring-teal-500 focus-visible:ring-offset-1',
  danger:
    'bg-status-danger-text text-white hover:bg-red-800 active:bg-red-900 border border-transparent shadow-sm focus-visible:ring-2 focus-visible:ring-red-500 focus-visible:ring-offset-2',
};

const sizeStyles: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 text-xs gap-1.5 rounded-md font-medium',
  md: 'h-10 px-4 text-sm gap-2 rounded-lg font-semibold',
  lg: 'h-12 px-6 text-base gap-2.5 rounded-lg font-semibold',
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  (
    {
      variant = 'primary',
      size = 'md',
      isLoading = false,
      leftIcon,
      rightIcon,
      fullWidth = false,
      className,
      disabled,
      children,
      type = 'button',
      ...props
    },
    ref,
  ) => {
    const isDisabled = disabled || isLoading;

    return (
      <button
        ref={ref}
        type={type}
        disabled={isDisabled}
        aria-busy={isLoading}
        className={cn(
          'inline-flex items-center justify-center transition-interact select-none cursor-pointer',
          'disabled:opacity-50 disabled:cursor-not-allowed disabled:pointer-events-none',
          variantStyles[variant],
          sizeStyles[size],
          fullWidth && 'w-full',
          className,
        )}
        {...props}
      >
        {isLoading && (
          <Loader2
            className="animate-spin motion-reduce:animate-none"
            size={size === 'sm' ? 14 : size === 'lg' ? 18 : 16}
            aria-hidden="true"
          />
        )}
        {!isLoading && leftIcon && <span className="inline-flex shrink-0" aria-hidden="true">{leftIcon}</span>}
        <span>{children}</span>
        {!isLoading && rightIcon && <span className="inline-flex shrink-0" aria-hidden="true">{rightIcon}</span>}
      </button>
    );
  },
);

Button.displayName = 'Button';
export default Button;
