import React, { forwardRef, useId } from 'react';
import { cn } from '@/lib/utils';

export interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  helperText?: string;
  error?: string;
  leftIcon?: React.ReactNode;
  rightIcon?: React.ReactNode;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(
  (
    {
      label,
      helperText,
      error,
      leftIcon,
      rightIcon,
      className,
      id: customId,
      disabled,
      required,
      ...props
    },
    ref,
  ) => {
    const generatedId = useId();
    const id = customId || generatedId;
    const errorId = `${id}-error`;
    const helperId = `${id}-helper`;

    return (
      <div className="w-full space-y-1.5">
        {label && (
          <label
            htmlFor={id}
            className="block text-xs font-semibold uppercase tracking-wider text-ink-secondary"
          >
            {label}
            {required && <span className="ml-1 text-status-danger-text" aria-hidden="true">*</span>}
          </label>
        )}

        <div className="relative flex items-center">
          {leftIcon && (
            <div
              className="pointer-events-none absolute left-3 flex items-center justify-center text-ink-muted"
              aria-hidden="true"
            >
              {leftIcon}
            </div>
          )}

          <input
            ref={ref}
            id={id}
            disabled={disabled}
            required={required}
            aria-invalid={Boolean(error)}
            aria-describedby={
              error ? errorId : helperText ? helperId : undefined
            }
            className={cn(
              'w-full h-10 rounded-lg border bg-surface-card px-3.5 text-sm text-ink-primary',
              'placeholder:text-ink-disabled transition-interact',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500 focus-visible:border-teal-500',
              'disabled:bg-surface-muted disabled:text-ink-disabled disabled:cursor-not-allowed',
              error
                ? 'border-status-danger-border focus-visible:ring-red-500 focus-visible:border-red-500 text-status-danger-text'
                : 'border-surface-border hover:border-surface-subtle',
              Boolean(leftIcon) && 'pl-10',
              Boolean(rightIcon) && 'pr-10',
              className,
            )}
            {...props}
          />

          {rightIcon && (
            <div
              className="absolute right-3 flex items-center justify-center text-ink-muted"
              aria-hidden="true"
            >
              {rightIcon}
            </div>
          )}
        </div>

        {error && (
          <p id={errorId} role="alert" className="text-xs font-medium text-status-danger-text">
            {error}
          </p>
        )}

        {!error && helperText && (
          <p id={helperId} className="text-xs text-ink-muted">
            {helperText}
          </p>
        )}
      </div>
    );
  },
);

Input.displayName = 'Input';
export default Input;
