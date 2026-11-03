import type { RazorpayCheckoutResponse, RazorpayOrderResponseDTO } from './types';

interface RazorpayCheckoutFailure {
  error?: {
    description?: string;
  };
}

interface RazorpayCheckoutOptions {
  key: string;
  amount: number;
  currency: string;
  name: string;
  order_id: string;
  handler: (response: RazorpayCheckoutResponse) => void;
  modal: {
    ondismiss: () => void;
  };
}

interface RazorpayInstance {
  open: () => void;
  on: (event: 'payment.failed', callback: (response: RazorpayCheckoutFailure) => void) => void;
}

declare global {
  interface Window {
    Razorpay?: new (options: RazorpayCheckoutOptions) => RazorpayInstance;
  }
}

let scriptPromise: Promise<void> | null = null;

function loadRazorpayScript(): Promise<void> {
  if (window.Razorpay) return Promise.resolve();
  if (scriptPromise) return scriptPromise;

  scriptPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = 'https://checkout.razorpay.com/v1/checkout.js';
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => {
      scriptPromise = null;
      reject(new Error('Unable to load Razorpay Checkout. Please try again.'));
    };
    document.body.appendChild(script);
  });
  return scriptPromise;
}

export async function openRazorpayCheckout(
  order: RazorpayOrderResponseDTO,
): Promise<RazorpayCheckoutResponse> {
  await loadRazorpayScript();
  if (!window.Razorpay) {
    throw new Error('Razorpay Checkout did not initialize.');
  }
  const Razorpay = window.Razorpay;

  return new Promise((resolve, reject) => {
    const checkout = new Razorpay({
      key: order.keyId,
      amount: order.amount,
      currency: order.currency,
      name: 'Railora',
      order_id: order.orderId,
      handler: resolve,
      modal: {
        ondismiss: () => reject(new Error('Payment checkout was closed before completion.')),
      },
    });
    checkout.on('payment.failed', (response) => {
      reject(new Error(response.error?.description || 'Razorpay payment failed.'));
    });
    checkout.open();
  });
}

export function isRazorpayCheckoutEnabled(): boolean {
  return import.meta.env.VITE_RAZORPAY_ENABLED === 'true';
}

export function isMockPaymentEnabled(): boolean {
  return import.meta.env.DEV || import.meta.env.MODE === 'test';
}

export function isSimulatedPaymentEnabled(): boolean {
  return import.meta.env.VITE_SIMULATED_PAYMENT_ENABLED === 'true';
}
