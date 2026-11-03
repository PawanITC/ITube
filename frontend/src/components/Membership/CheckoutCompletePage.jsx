import { useEffect, useState } from 'react';
import { apiFetch } from './api.js';

const POLL_INTERVAL_MS = 2000;
const MAX_POLLS = 30;

/**
 * Where Stripe sends the viewer after Checkout. It only *shows* status: the membership is created
 * by the backend when Stripe's webhook confirms payment, never by landing on this page.
 */
export default function CheckoutCompletePage({ onOpenChannel, onOpenMemberships }) {
  const sessionId = new URLSearchParams(window.location.search).get('session_id');
  const [status, setStatus] = useState(null);
  const [state, setState] = useState(sessionId ? 'waiting' : 'missing');

  useEffect(() => {
    if (!sessionId) return undefined;
    let cancelled = false;
    let polls = 0;
    let timer;
    async function poll() {
      try {
        const result = await apiFetch(`/api/payments/checkout/sessions/${encodeURIComponent(sessionId)}`);
        if (cancelled) return;
        setStatus(result);
        if (result.membership?.hasAccess) return setState('active');
        if (result.status === 'EXPIRED') return setState('expired');
      } catch (error) {
        if (cancelled) return;
        if (error.status === 404 || error.status === 401) return setState('missing');
      }
      polls += 1;
      if (polls >= MAX_POLLS) setState('slow');
      else timer = setTimeout(poll, POLL_INTERVAL_MS);
    }
    poll();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [sessionId]);

  const membership = status?.membership;
  return (
    <main className="memberships-page">
      <div className="checkout-result" role="status" aria-live="polite">
        {state === 'waiting' && <><div className="spinner" aria-hidden="true" /><h1>Confirming your payment…</h1><p>This usually takes a few seconds.</p></>}
        {state === 'active' && <><h1>Welcome, you're a member!</h1><p>You joined <strong>{membership.channelName}</strong> on the <strong>{membership.tierName}</strong> tier. Members-only videos are unlocked.</p></>}
        {state === 'slow' && <><h1>Payment received, still confirming</h1><p>Your bank or Stripe is taking longer than usual. Your membership will appear on your memberships page as soon as it's confirmed. You won't be charged twice.</p></>}
        {state === 'expired' && <><h1>Checkout expired</h1><p>No payment was taken. You can join again from the channel page.</p></>}
        {state === 'missing' && <><h1>We couldn't find that checkout</h1><p>If you completed a payment, check your memberships page.</p></>}
        <div className="checkout-actions">
          {status?.channelId && <button type="button" className="join-button" onClick={() => onOpenChannel(status.channelId)}>Back to channel</button>}
          <button type="button" className="subscribe-button" onClick={onOpenMemberships}>My memberships</button>
        </div>
      </div>
    </main>
  );
}
