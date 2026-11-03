// Small fetch helpers for the payments/memberships API. Errors carry the server's problem `detail`.

export async function apiFetch(url, options = {}) {
  const response = await fetch(url, {
    credentials: 'include',
    ...options,
    headers: options.body ? { 'Content-Type': 'application/json', ...options.headers } : options.headers,
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    const error = new Error(problem?.detail || (response.status === 401
      ? 'Click the ITube logo to start a session first.'
      : 'Something went wrong. Please try again.'));
    error.status = response.status;
    throw error;
  }
  return response.status === 204 ? null : response.json();
}

export function formatPrice(minor, currency = 'gbp') {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency: currency.toUpperCase() }).format(minor / 100);
}

export function formatDate(value) {
  if (!value) return '';
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(value));
}

export const PERK_LABELS = {
  MEMBERS_ONLY_VIDEOS: 'Members-only videos',
  MEMBERS_ONLY_SHORTS: 'Members-only Shorts',
  MEMBERS_ONLY_LIVESTREAMS: 'Members-only livestreams',
  EARLY_ACCESS: 'Early access to videos',
  COMMUNITY_POSTS: 'Exclusive community posts',
  LOYALTY_BADGE: 'Loyalty badge',
  CUSTOM_EMOJI: 'Custom emoji',
  MEMBERS_ONLY_CHAT: 'Members-only live chat',
  CUSTOM: 'Other',
};

/** Sends the browser to Stripe-hosted Checkout. Card details are entered on Stripe, never on iTube. */
export async function startMembershipCheckout(tierId) {
  const { checkoutUrl } = await apiFetch('/api/payments/checkout/channel-membership', {
    method: 'POST',
    body: JSON.stringify({ tierId }),
  });
  window.location.assign(checkoutUrl);
}
