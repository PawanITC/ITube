import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import WatchPage from './WatchPage';
import {
  STALL_QUIET_MS, UPGRADE_COOLDOWN_MS, decideQuality, pickQuality, stepDown, toLevels, updateEstimate,
} from './adaptiveQuality';

vi.mock('../Membership/JoinButton.jsx', () => ({ default: () => null }));

const levels = toLevels([{ quality: '720p', sizeBytes: null }, { quality: '360p', sizeBytes: null }], 0);

describe('choosing a quality', () => {
  test('levels are sorted lowest first and use real bitrates when the size is known', () => {
    expect(levels.map(level => level.quality)).toEqual(['360p', '720p']);
    // 15 MB over 60 s = 2000 kbps
    expect(toLevels([{ quality: '720p', sizeBytes: 15_000_000 }], 60)[0].kbps).toBeCloseTo(2000);
  });

  test('picks the highest quality the connection can sustain, else the lowest', () => {
    expect(pickQuality(levels, 10_000)).toBe('720p');
    expect(pickQuality(levels, 2_000)).toBe('360p');
    expect(pickQuality(levels, 100)).toBe('360p');
    expect(pickQuality(levels, null)).toBe('360p');
  });

  test('goes down straight away but only goes up once settled', () => {
    const now = 1_000_000;
    expect(decideQuality({ levels, current: '720p', bandwidthKbps: 1_000, lastSwitchAt: now - 1000, now })).toBe('360p');
    expect(decideQuality({ levels, current: '360p', bandwidthKbps: 10_000, lastSwitchAt: now - 1000, now })).toBe('360p');
    expect(decideQuality({ levels, current: '360p', bandwidthKbps: 10_000, lastSwitchAt: now - UPGRADE_COOLDOWN_MS, lastStallAt: now - 1000, now })).toBe('360p');
    expect(decideQuality({ levels, current: '360p', bandwidthKbps: 10_000, lastSwitchAt: now - UPGRADE_COOLDOWN_MS, lastStallAt: now - STALL_QUIET_MS, now })).toBe('720p');
    // Nothing playing yet: start at the best fit
    expect(decideQuality({ levels, current: null, bandwidthKbps: 10_000, now })).toBe('720p');
  });

  test('steps down one level and smooths estimates, reacting faster to drops', () => {
    expect(stepDown(levels, '720p')).toBe('360p');
    expect(stepDown(levels, '360p')).toBe('360p');
    expect(updateEstimate(null, 5000)).toBe(5000);
    expect(5000 - updateEstimate(5000, 1000)).toBeGreaterThan(updateEstimate(1000, 5000) - 1000);
  });
});

describe('the player', () => {
  const video = { videoId: 25, channelId: 2, title: 'Video 25', channelName: 'Test', duration: '00:05:00', processingStatus: 'READY', visibility: 'PUBLIC' };
  const json = body => ({ ok: true, status: 200, json: async () => body });

  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async url => {
      if (url.startsWith('/api/videos/bandwidth-probe')) {
        // A fast connection: 512 KB arriving "instantly"
        return { ok: true, status: 200, arrayBuffer: async () => new ArrayBuffer(512 * 1024) };
      }
      if (url === '/api/videos/25/quality-levels') return json([{ quality: '360p', sizeBytes: 20_000_000 }, { quality: '720p', sizeBytes: 60_000_000 }]);
      if (url.endsWith('/playback-authorisation')) return json({ videoId: 25, allowed: true, sourceUrl: '/api/videos/25/file' });
      if (url === '/api/videos') return json([video]);
      if (url === '/api/videos/25/details') return json({ videoId: 25, description: null, viewCount: 0, createdAt: null });
      return json([]);
    }));
  });
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

  test('Auto measures the connection, plays the best rendition, and the menu has no Original', async () => {
    const { container } = render(<WatchPage videoId={25} videos={[video]} videosLoading={false} videosError="" session={null} />);

    await waitFor(() => expect(container.querySelector('video.watch-player')?.getAttribute('src')).toBe('/api/videos/25/renditions/720p'));
    expect(fetch.mock.calls.some(([url]) => url.startsWith('/api/videos/bandwidth-probe'))).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: 'Player settings' }));
    expect(screen.getByRole('menuitem', { name: /Quality/ })).toHaveTextContent('Auto (720p)');
    fireEvent.click(screen.getByRole('menuitem', { name: /Quality/ }));
    const options = screen.getAllByRole('menuitemradio').map(option => option.textContent);
    expect(options).toEqual(['720p', '360p', '✓Auto (720p)']);
    expect(options.join(' ')).not.toMatch(/Original/);

    // Picking a fixed quality switches to it
    fireEvent.click(screen.getByRole('menuitemradio', { name: /360p/ }));
    await waitFor(() => expect(container.querySelector('video.watch-player').getAttribute('src')).toBe('/api/videos/25/renditions/360p'));
  });
});
