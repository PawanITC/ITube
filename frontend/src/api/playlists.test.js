import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { loadPlaylistQueue } from './playlists';

beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
afterEach(() => vi.unstubAllGlobals());
const response = body => ({ ok: true, status: 200, json: async () => body });

test('loads all playlist pages in saved order and forwards cancellation', async () => {
  fetch.mockResolvedValueOnce(response({ playlist: { version: 'v1' }, videos: [{ videoId: 25 }], hasMore: true }))
    .mockResolvedValueOnce(response({ playlist: { version: 'v1' }, videos: [{ videoId: 26 }], hasMore: false }));
  const controller = new AbortController();
  const result = await loadPlaylistQueue('p1', controller.signal);
  expect(result.videos.map(entry => entry.videoId)).toEqual([25, 26]);
  expect(fetch.mock.calls[1][0]).toBe('/api/playlists/p1?page=1&size=100');
  expect(fetch.mock.calls.every(([, options]) => options.signal === controller.signal)).toBe(true);
});

test('does not combine pages from conflicting playlist versions', async () => {
  fetch.mockResolvedValueOnce(response({ playlist: { version: 'v1' }, videos: [{ videoId: 25 }], hasMore: true }))
    .mockResolvedValueOnce(response({ playlist: { version: 'v2' }, videos: [{ videoId: 26 }], hasMore: false }));
  await expect(loadPlaylistQueue('p1')).rejects.toThrow('Playlist changed');
});

test('playback reads the existing automatic snapshot rather than generating a new order', async () => {
  fetch.mockResolvedValue(response({ playlist: { version: 'snapshot1' }, videos: [{ videoId: 25 }, { videoId: 26 }], hasMore: false }));
  const result = await loadPlaylistQueue('automatic:snapshot1');
  expect(result.videos.map(entry => entry.videoId)).toEqual([25, 26]);
  expect(fetch.mock.calls[0][0]).toBe('/api/playlists/automatic/snapshot1?page=0&size=100');
});
