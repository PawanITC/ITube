// Keeps the user signed in. The access token (an HttpOnly cookie) expires after 15 minutes; when an API call answers
// 401, this swaps the refresh token cookie for a new pair (POST /api/auth/refresh) and retries the call once.
// Scripts never see either token. Parallel 401s share one refresh, so two calls never race to rotate the token.

const NEVER_REFRESH = /^\/api\/auth\/(login|register|refresh|logout)$/;
let refreshing = null;
let baseFetch = (...args) => fetch(...args);

/** True when the browser now has a fresh access token. */
export function refreshAccessToken() {
  if (!refreshing) {
    refreshing = baseFetch('/api/auth/refresh', { method: 'POST', credentials: 'include' })
      .then(response => response.ok)
      .catch(() => false)
      .finally(() => { refreshing = null; });
  }
  return refreshing;
}

function sameOriginApiPath(input, origin) {
  try {
    const url = new URL(typeof input === 'string' || input instanceof URL ? input : input.url, origin);
    return url.origin === origin && url.pathname.startsWith('/api/') ? url.pathname : null;
  } catch {
    return null;
  }
}

/** Wraps window.fetch once, at start-up (src/index.jsx). */
export function installTokenRefresh(target = window) {
  if (target.fetch.itubeTokenRefresh) return;
  const original = target.fetch.bind(target);
  baseFetch = original;
  const wrapped = async (input, init) => {
    // A Request body can be read only once: keep a copy for the retry
    const retryInput = input instanceof Request ? input.clone() : input;
    const response = await original(input, init);
    if (response.status !== 401) return response;
    const path = sameOriginApiPath(input, target.location.origin);
    if (!path || NEVER_REFRESH.test(path)) return response;
    if (!(await refreshAccessToken())) return response;
    return original(retryInput, init);
  };
  wrapped.itubeTokenRefresh = true;
  target.fetch = wrapped;
}

/** Starts "Continue with Cognito": a full-page redirect to the Cognito sign-in page and back. */
export function signInWithCognito() {
  window.location.assign('/api/auth/oauth/cognito/authorize');
}

/** Which sign-in options the server offers, e.g. { password: true, cognito: false }. */
export async function fetchSignInProviders() {
  try {
    const response = await fetch('/api/auth/providers', { credentials: 'include' });
    return response.ok ? await response.json() : { password: true, cognito: false };
  } catch {
    return { password: true, cognito: false };
  }
}

const COGNITO_ERRORS = {
  'cognito-cancelled': 'Sign-in with Cognito was cancelled.',
  'cognito-state': 'That sign-in link expired or came from another browser. Please try again.',
  'cognito-unverified': 'Verify your email address with Cognito, then try again.',
  'cognito-unavailable': 'Cognito could not be reached. Please try again in a moment.',
};

/** The message for ?error=... after a failed Cognito sign-in, or ''. */
export function cognitoErrorMessage(search = window.location.search) {
  const code = new URLSearchParams(search).get('error');
  if (!code || !code.startsWith('cognito-')) return '';
  return COGNITO_ERRORS[code] || 'Sign-in with Cognito didn’t work. Please try again.';
}
