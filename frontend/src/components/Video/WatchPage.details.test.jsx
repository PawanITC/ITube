import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import WatchPage from './WatchPage';
import { formatAge, formatViews } from './VideoDescription';

vi.mock('../Membership/JoinButton.jsx', () => ({ default: () => null }));

const video = { videoId: 25, channelId: 2, title: 'Video 25', channelName: 'Test', processingStatus: 'READY', visibility: 'PUBLIC' };
const response = (body, status = 200) => ({ ok: status < 400, status, json: async () => body });
const viewPosts = () => fetch.mock.calls.filter(([url, options]) => url === '/api/videos/25/views' && options?.method === 'POST');

let viewCounted = true;

beforeEach(() => {
  viewCounted = true;
  window.localStorage.clear();
  vi.stubGlobal('fetch', vi.fn(async (url, options) => {
    if (url === '/api/videos/25/views') return response({ counted: viewCounted });
    if (url === '/api/videos/25/details') {
      return response({ videoId: 25, description: 'Line one\nLine two', viewCount: 41, createdAt: new Date().toISOString() });
    }
    if (url.endsWith('/playback-authorisation')) return response({ videoId: 25, allowed: true, sourceUrl: '/api/videos/25/file' });
    if (url === '/api/videos') return response([video]);
    if (url === '/api/history') return response({ id: 1 }, 201);
    return response([]);
  }));
});
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

test('shows the description and view count, and counts one view per visit', async () => {
  const { container } = render(<WatchPage videoId={25} videos={[video]} videosLoading={false} videosError="" session={{ userId: 7 }} />);

  expect(await screen.findByText('41 views')).toBeInTheDocument();
  expect(screen.getByText(/Line one/)).toBeInTheDocument();

  const player = await waitFor(() => {
    const element = container.querySelector('video.watch-player');
    if (!element) throw new Error('player not ready');
    return element;
  });
  fireEvent.playing(player);
  fireEvent.playing(player);

  expect(await screen.findByText('42 views')).toBeInTheDocument();
  expect(viewPosts()).toHaveLength(1);
});

test('formats views and upload age', () => {
  expect(formatViews(1)).toBe('1 view');
  expect(formatViews(1234)).toBe(`${new Intl.NumberFormat().format(1234)} views`);
  const now = Date.parse('2026-11-23T12:00:00Z');
  expect(formatAge('2026-11-20T12:00:00Z', now)).toBe('3 days ago');
  expect(formatAge('2026-11-23T11:59:30Z', now)).toBe('just now');
});

test('sends a stable viewer id, and a repeat viewer does not raise the count', async () => {
  viewCounted = false;
  const { container } = render(<WatchPage videoId={25} videos={[video]} videosLoading={false} videosError="" session={null} />);
  expect(await screen.findByText('41 views')).toBeInTheDocument();
  const player = await waitFor(() => {
    const element = container.querySelector('video.watch-player');
    if (!element) throw new Error('player not ready');
    return element;
  });
  fireEvent.playing(player);

  await waitFor(() => expect(viewPosts()).toHaveLength(1));
  const sentId = viewPosts()[0][1].headers['X-Viewer-Id'];
  expect(sentId).toMatch(/^[A-Za-z0-9-]{8,48}$/);
  expect(window.localStorage.getItem('itube-viewer-id')).toBe(sentId);
  await new Promise(resolve => setTimeout(resolve, 0));
  expect(screen.getByText('41 views')).toBeInTheDocument();
});
