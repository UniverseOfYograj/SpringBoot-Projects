/**
 * Utility helpers.
 */

/**
 * Joins class name strings, filtering falsy values.
 * Keeps the bundle lean — no external clsx dependency needed at this stage.
 */
export function cn(...classes: (string | undefined | null | false)[]): string {
  return classes.filter(Boolean).join(' ');
}

/**
 * Formats a LocalDate string (YYYY-MM-DD) to a human-readable label.
 * @example "2026-10-26" → "26 Oct 2026"
 */
export function formatJourneyDate(isoDate: string): string {
  const date = new Date(isoDate + 'T00:00:00');
  return date.toLocaleDateString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  });
}

/**
 * Formats a LocalTime string (HH:mm:ss) to HH:MM display.
 * @example "08:30:00" → "08:30"
 */
export function formatTime(time: string | null | undefined): string {
  if (!time) return '—';
  return time.substring(0, 5);
}

/**
 * Formats an INR amount from a BigDecimal string.
 * @example "500.00" → "₹500"
 */
export function formatCurrency(amount: number | string | null | undefined): string {
  if (amount == null) return '—';
  const num = typeof amount === 'string' ? parseFloat(amount) : amount;
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(num);
}
