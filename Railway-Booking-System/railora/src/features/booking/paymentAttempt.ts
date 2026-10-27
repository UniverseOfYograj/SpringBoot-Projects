import type { PaymentRequestDTO } from './types';

export interface PaymentAttempt extends PaymentRequestDTO {
  paymentMethod: 'MOCK_UPI';
  idempotencyKey: string;
  simulateFailure: boolean;
}

function storageKey(bookingRef: string): string {
  return `railora:payment-attempt:${bookingRef}`;
}

function isPaymentAttempt(value: unknown): value is PaymentAttempt {
  if (!value || typeof value !== 'object') return false;
  const attempt = value as Partial<PaymentAttempt>;
  return (
    typeof attempt.idempotencyKey === 'string' &&
    attempt.idempotencyKey.length > 0 &&
    attempt.idempotencyKey.length <= 64 &&
    attempt.paymentMethod === 'MOCK_UPI' &&
    typeof attempt.simulateFailure === 'boolean'
  );
}

export function getOrCreatePaymentAttempt(
  bookingRef: string,
  simulateFailure: boolean,
): PaymentAttempt {
  const key = storageKey(bookingRef);
  const storedAttempt = sessionStorage.getItem(key);
  if (storedAttempt !== null) {
    let parsedAttempt: unknown;
    try {
      parsedAttempt = JSON.parse(storedAttempt);
    } catch {
      throw new Error('Unable to safely resume the previous payment attempt in this tab.');
    }

    if (!isPaymentAttempt(parsedAttempt)) {
      throw new Error('Unable to safely resume the previous payment attempt in this tab.');
    }
    return parsedAttempt;
  }

  const attempt: PaymentAttempt = {
    paymentMethod: 'MOCK_UPI',
    idempotencyKey: `PAY-${crypto.randomUUID()}`,
    simulateFailure,
  };
  sessionStorage.setItem(key, JSON.stringify(attempt));
  return attempt;
}

export function clearPaymentAttempt(bookingRef: string): void {
  sessionStorage.removeItem(storageKey(bookingRef));
}
