import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { engagementFetch, setEngagementAccount, waitForOperation } from './engagement';

const response = (body, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body });
const accepted = id => response({ operationId: id, state: 'PENDING' }, 202);
beforeEach(() => { vi.useFakeTimers(); vi.stubGlobal('fetch', vi.fn()); setEngagementAccount('test'); });
afterEach(() => { setEngagementAccount(undefined); vi.useRealTimers(); vi.unstubAllGlobals(); });

test('waits for database confirmation and returns the original result shape', async () => {
  fetch.mockResolvedValueOnce(accepted('one'))
    .mockResolvedValueOnce(response({ state: 'PENDING' }))
    .mockResolvedValueOnce(response({ state: 'SUCCEEDED', result: { id: 'playlist', title: 'Learning' } }));
  const request = engagementFetch('/api/playlists', { method: 'POST' });
  await vi.advanceTimersByTimeAsync(300);
  expect(await (await request).json()).toEqual({ id: 'playlist', title: 'Learning' });
  expect(fetch.mock.calls[1][0]).toBe('/api/engagement/operations/one');
  expect(fetch.mock.calls[0][1].headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
});

test('consumer rejection reports the real validation failure', async () => {
  fetch.mockResolvedValueOnce(accepted('one'))
    .mockResolvedValueOnce(response({ state: 'FAILED', httpStatus: 409, message: 'Playlist changed. Refresh it and try again.' }));
  await expect(engagementFetch('/api/playlists/p1', { method: 'PATCH' }))
    .rejects.toMatchObject({ status: 409, message: 'Playlist changed. Refresh it and try again.' });
});

test('a network retry reuses the same idempotency key', async () => {
  fetch.mockRejectedValueOnce(new TypeError('Network lost'))
    .mockResolvedValueOnce(accepted('one'))
    .mockResolvedValueOnce(response({ state: 'SUCCEEDED', result: null }));
  expect((await engagementFetch('/api/liked-videos/25', { method: 'PUT' })).status).toBe(204);
  expect(fetch.mock.calls[0][1].headers['Idempotency-Key']).toBe(fetch.mock.calls[1][1].headers['Idempotency-Key']);
});

test('Kafka publish failures are not reported as completed writes or polled', async () => {
  fetch.mockResolvedValueOnce(response({ detail: 'Kafka unavailable' }, 503));
  expect((await engagementFetch('/api/saved-videos/25', { method: 'PUT' })).status).toBe(503);
  expect(fetch).toHaveBeenCalledTimes(1);
});

test('navigating to History waits for the outstanding watch write', async () => {
  fetch.mockResolvedValueOnce(accepted('watch'))
    .mockResolvedValueOnce(response({ state: 'PENDING' }))
    .mockResolvedValueOnce(response({ state: 'SUCCEEDED', result: { id: 'entry' } }))
    .mockResolvedValueOnce(response([{ id: 'entry' }]));
  const write = engagementFetch('/api/history', { method: 'POST' });
  const read = engagementFetch('/api/history');
  await vi.advanceTimersByTimeAsync(300);
  await write;
  expect(await (await read).json()).toEqual([{ id: 'entry' }]);
  expect(fetch.mock.calls.map(([url]) => url)).toEqual([
    '/api/history', '/api/engagement/operations/watch', '/api/engagement/operations/watch', '/api/history',
  ]);
});

test('a delayed consumer gives a queued message instead of false success', async () => {
  fetch.mockResolvedValue(response({ state: 'PENDING' }));
  const request = waitForOperation('one', undefined, 1000);
  const assertion = expect(request).rejects.toMatchObject({ pending: true, operationId: 'one' });
  await vi.advanceTimersByTimeAsync(1100);
  await assertion;
});

test('the polling deadline also cancels a network request that never answers', async () => {
  fetch.mockImplementation((url, options) => new Promise((resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
  }));
  const request = waitForOperation('one', undefined, 1000);
  const assertion = expect(request).rejects.toMatchObject({ pending: true, operationId: 'one' });
  await vi.advanceTimersByTimeAsync(1100);
  await assertion;
  expect(fetch.mock.calls[0][1].signal.aborted).toBe(true);
});

test('cancelling a page stops its operation polling', async () => {
  fetch.mockResolvedValue(response({ state: 'PENDING' }));
  const controller = new AbortController();
  const request = waitForOperation('one', controller.signal);
  const assertion = expect(request).rejects.toMatchObject({ name: 'AbortError' });
  await vi.advanceTimersByTimeAsync(1);
  controller.abort();
  await assertion;
  expect(fetch).toHaveBeenCalledTimes(1);
});

test('switching account cancels old polling and does not block the new account', async () => {
  fetch.mockResolvedValueOnce(accepted('old-account'))
    .mockResolvedValueOnce(response({ state: 'PENDING' }))
    .mockResolvedValueOnce(response([]));
  const old = engagementFetch('/api/history', { method: 'POST' });
  const assertion = expect(old).rejects.toMatchObject({ name: 'AbortError' });
  await vi.advanceTimersByTimeAsync(1);
  setEngagementAccount('different-account');
  expect(await (await engagementFetch('/api/history')).json()).toEqual([]);
  await vi.advanceTimersByTimeAsync(300);
  await assertion;
});
