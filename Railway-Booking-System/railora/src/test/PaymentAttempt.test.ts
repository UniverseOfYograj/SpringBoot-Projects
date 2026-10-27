import { beforeEach, describe, expect, it } from 'vitest';
import { clearPaymentAttempt, getOrCreatePaymentAttempt } from '@/features/booking/paymentAttempt';

describe('payment attempt idempotency', () => {
  const bookingRef = 'BKG-20261026-MAS02';

  beforeEach(() => {
    sessionStorage.clear();
  });

  it('reuses the same key and original parameters while the outcome is unknown', () => {
    const initialAttempt = getOrCreatePaymentAttempt(bookingRef, true);
    const retriedAttempt = getOrCreatePaymentAttempt(bookingRef, false);

    expect(retriedAttempt).toEqual(initialAttempt);
    expect(retriedAttempt.idempotencyKey).toMatch(/^PAY-/);
    expect(retriedAttempt.simulateFailure).toBe(true);
  });

  it('creates a new key only after the previous definitive failure is cleared', () => {
    const failedAttempt = getOrCreatePaymentAttempt(bookingRef, true);
    clearPaymentAttempt(bookingRef);
    const retryAttempt = getOrCreatePaymentAttempt(bookingRef, false);

    expect(retryAttempt.idempotencyKey).not.toBe(failedAttempt.idempotencyKey);
    expect(retryAttempt.simulateFailure).toBe(false);
  });
});
