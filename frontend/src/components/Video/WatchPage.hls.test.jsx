import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import WatchPage from './WatchPage';

vi.mock('../Membership/JoinButton.jsx', () => ({ default: () => null }));

// A stand-in for hls.js: records what the player asks of it and lets the test fire its events
const hlsInstances = [];
vi.mock('hls.js', () => {
  class FakeHls {
    static Events = { MANIFEST_PARSED: 'manifestParsed', LEVEL_SWITCHED: 'levelSwitched', ERROR: 'error' };
    static ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' };
    constructor(config) {
      this.config = config;
      this.handlers = {};
      this.levels = [{ name: '360p', bitrate: 1_300_000 }, { name: '720p', bitrate: 3_900_000 }];
      this.currentLevel = -1;
      this.destroyed = false;
      hlsInstances.push(this);
    }
    on(event, handler) { this.handlers[event] = handler; }
    emit(event, data = {}) { this.handlers[event]?.(event, data); }
    loadSource(url) { this.source = url; }
    attachMedia(media) { this.media = media; }
    destroy() { this.destroyed = true; }
  }
  return { default: FakeHls };
});

const video = { videoId: 25, channelId: 2, title: 'Video 25', channelName: 'Test', duration: '00:05:00', processingStatus: 'READY', visibility: 'PUBLIC' };
const json = body => ({ ok: true, status: 200, json: async () => body });

beforeEach(() => {
  hlsInstances.length = 0;
  vi.stubGlobal('MediaSource', { isTypeSupported: () => true });
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.stubGlobal('fetch', vi.fn(async url => {
    if (url === '/api/videos/25/quality-levels') {
      return json([{ quality: '360p', sizeBytes: 20_000_000, format: 'hls' }, { quality: '720p', sizeBytes: 60_000_000, format: 'hls' }]);
    }
    if (url.endsWith('/playback-authorisation')) return json({ videoId: 25, allowed: true, sourceUrl: '/api/videos/25/file' });
    if (url === '/api/videos') return json([video]);
    if (url === '/api/videos/25/details') return json({ videoId: 25, description: null, viewCount: 0, createdAt: null });
    return json([]);
  }));
});
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

test('HLS videos stream through hls.js with Auto and fixed qualities, and no Original', async () => {
  const { container } = render(<WatchPage videoId={25} videos={[video]} videosLoading={false} videosError="" session={null} />);

  await waitFor(() => expect(hlsInstances).toHaveLength(1));
  const hls = hlsInstances[0];
  const player = container.querySelector('video.watch-player');
  expect(hls.source).toBe('/api/videos/25/hls/master.m3u8');
  expect(hls.media).toBe(player);
  expect(player.getAttribute('src')).toBeNull();
  // No bandwidth probe: hls.js measures every segment itself
  expect(fetch.mock.calls.some(([url]) => url.startsWith('/api/videos/bandwidth-probe'))).toBe(false);

  act(() => {
    hls.emit('manifestParsed');
    hls.emit('levelSwitched', { level: 1 });
  });

  fireEvent.click(screen.getByRole('button', { name: 'Player settings' }));
  expect(screen.getByRole('menuitem', { name: /Quality/ })).toHaveTextContent('Auto (720p)');
  fireEvent.click(screen.getByRole('menuitem', { name: /Quality/ }));
  expect(screen.getAllByRole('menuitemradio').map(option => option.textContent)).toEqual(['720p', '360p', '✓Auto (720p)']);

  // A fixed quality pins hls.js to that level; Auto hands the choice back
  fireEvent.click(screen.getByRole('menuitemradio', { name: /360p/ }));
  expect(hls.currentLevel).toBe(0);
  fireEvent.click(screen.getByRole('button', { name: 'Player settings' }));
  fireEvent.click(screen.getByRole('menuitem', { name: /Quality/ }));
  fireEvent.click(screen.getByRole('menuitemradio', { name: /Auto/ }));
  expect(hls.currentLevel).toBe(-1);
});

test('a stream hls.js cannot recover falls back to the uploaded file', async () => {
  const { container } = render(<WatchPage videoId={25} videos={[video]} videosLoading={false} videosError="" session={null} />);
  await waitFor(() => expect(hlsInstances).toHaveLength(1));
  const hls = hlsInstances[0];
  hls.startLoad = vi.fn();

  act(() => {
    for (let attempt = 0; attempt < 4; attempt += 1) hls.emit('error', { fatal: true, type: 'networkError', response: { code: 500 } });
  });

  await waitFor(() => expect(container.querySelector('video.watch-player').getAttribute('src')).toBe('/api/videos/25/file'));
  expect(hls.startLoad).toHaveBeenCalledTimes(3);
  expect(hls.destroyed).toBe(true);
});

test('an older video switched to HLS mid-play (backfill) carries on with HLS when its MP4 file disappears', async () => {
  let hlsReady = false;
  fetch.mockImplementation(async url => {
    if (url.startsWith('/api/videos/bandwidth-probe')) return { ok: true, status: 200, arrayBuffer: async () => new ArrayBuffer(512 * 1024) };
    if (url === '/api/videos/25/quality-levels') {
      return json(hlsReady
        ? [{ quality: '360p', sizeBytes: 20_000_000, format: 'hls' }, { quality: '720p', sizeBytes: 60_000_000, format: 'hls' }]
        : [{ quality: '360p', sizeBytes: 20_000_000, format: 'mp4' }, { quality: '720p', sizeBytes: 60_000_000, format: 'mp4' }]);
    }
    if (url.endsWith('/playback-authorisation')) return json({ videoId: 25, allowed: true, sourceUrl: '/api/videos/25/file' });
    if (url === '/api/videos') return json([video]);
    if (url === '/api/videos/25/details') return json({ videoId: 25, description: null, viewCount: 0, createdAt: null });
    return json([]);
  });
  const { container } = render(<WatchPage videoId={25} videos={[video]} videosLoading={false} videosError="" session={null} />);
  const player = () => container.querySelector('video.watch-player');
  await waitFor(() => expect(player().getAttribute('src')).toBe('/api/videos/25/renditions/720p'));
  expect(hlsInstances).toHaveLength(0);

  hlsReady = true;
  fireEvent.error(player());

  await waitFor(() => expect(hlsInstances).toHaveLength(1));
  expect(hlsInstances[0].source).toBe('/api/videos/25/hls/master.m3u8');
  expect(player().getAttribute('src')).toBeNull();
});
