import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { installTokenRefresh } from './session';

function response(status) {
  return { ok: status >= 200 && status < 300, status, json: async () => ({}) };
}

let target;
let calls;

beforeEach(() => {
  calls = [];
  target = { location: { origin: 'http://localhost' }, fetch: null };
});
afterEach(() => vi.unstubAllGlobals());

function install(handler) {
  target.fetch = vi.fn(async (url, options) => { calls.push(url); return handler(url, options); });
  // refreshAccessToken() goes through the same (unwrapped) fetch
  installTokenRefresh(target);
}

test('a 401 refreshes the access token once and retries the call', async () => {
  let fresh = false;
  install(url => {
    if (url === '/api/auth/refresh') { fresh = true; return response(200); }
    return response(fresh ? 200 : 401);
  });
  const result = await target.fetch('/api/history', { credentials: 'include' });
  expect(result.status).toBe(200);
  expect(calls).toEqual(['/api/history', '/api/auth/refresh', '/api/history']);
});

test('parallel 401s share one refresh', async () => {
  let fresh = false;
  install(async url => {
    if (url === '/api/auth/refresh') { await new Promise(resolve => setTimeout(resolve, 5)); fresh = true; return response(200); }
    return response(fresh ? 200 : 401);
  });
  const results = await Promise.all([target.fetch('/api/history'), target.fetch('/api/liked-videos')]);
  expect(results.map(result => result.status)).toEqual([200, 200]);
  expect(calls.filter(url => url === '/api/auth/refresh')).toHaveLength(1);
});

test('when the refresh fails the original 401 is returned', async () => {
  install(() => response(401));
  const result = await target.fetch('/api/history');
  expect(result.status).toBe(401);
  expect(calls).toEqual(['/api/history', '/api/auth/refresh']);
});

test('sign-in calls and other sites are never refreshed', async () => {
  install(() => response(401));
  await target.fetch('/api/auth/login', { method: 'POST' });
  await target.fetch('https://bucket.s3.amazonaws.com/part?sig=x', { method: 'PUT' });
  expect(calls).toEqual(['/api/auth/login', 'https://bucket.s3.amazonaws.com/part?sig=x']);
});
