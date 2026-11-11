import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, vi } from 'vitest';
import App from './App';

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [] }));
});
afterEach(() => vi.unstubAllGlobals());

test('renders the video feed', () => {
  render(<App />);
  expect(screen.getByRole('main')).toBeInTheDocument();
});

test('History thumbnail opens the correct unlisted video in the existing player', async () => {
  const video = { videoId: 25, channelId: 2, title: 'A watched video', channelName: 'Test channel', thumbnailUrl: '/api/videos/25/thumbnail', visibility: 'UNLISTED', processingStatus: 'READY' };
  fetch.mockImplementation(async (url, options) => {
    let body = [];
    if (url === '/api/session/current') body = { userId: 7, channelId: 2 };
    else if (url.startsWith('/api/history?')) body = [{ id: 1, videoId: 25, watchedAt: '2026-11-10T10:00:00Z', video }];
    else if (url === '/api/history' && options?.method === 'POST') return { ok: true, status: 201, json: async () => ({ id: 2, videoId: 25 }) };
    else if (url.endsWith('/playback-authorisation')) body = { videoId: 25, allowed: true, sourceUrl: '/api/videos/25/file' };
    else if (url.includes('/tiers')) body = { owner: false, tiers: [] };
    return { ok: true, status: 200, json: async () => body };
  });
  const { container, unmount } = render(<App />);
  try {
    fireEvent.click(screen.getAllByRole('link', { name: 'History' })[0]);
    expect(await screen.findByRole('img', { name: 'Thumbnail for A watched video' })).toHaveAttribute('src', video.thumbnailUrl);
    fireEvent.click(screen.getByRole('link', { name: 'Play A watched video' }));
    expect(await screen.findByRole('heading', { name: 'A watched video' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/watch/25');
    await waitFor(() => expect(container.querySelector('video')).toHaveAttribute('src', '/api/videos/25/file'));
    expect(container.querySelector('video')).toHaveAttribute('poster', video.thumbnailUrl);
    expect(container.querySelector('video')).toHaveAttribute('src', '/api/videos/25/file');
    fireEvent.playing(container.querySelector('video'));
    expect(fetch.mock.calls.some(([url, options]) => url === '/api/history' && options?.method === 'POST' && JSON.parse(options.body).videoId === 25)).toBe(true);
  } finally {
    unmount();
    window.history.replaceState(null, '', '/');
  }
});

test('opens History through hash navigation and returns home', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [] }));
  const { unmount } = render(<App />);
  try {
    await act(async () => {
      window.history.replaceState(null, '', '#history');
      window.dispatchEvent(new Event('hashchange'));
    });
    expect(await screen.findByRole('heading', { name: 'Watch history' })).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'History' }).every(link => link.getAttribute('aria-current') === 'page')).toBe(true);
    await act(async () => {
      window.history.replaceState(null, '', '#home');
      window.dispatchEvent(new Event('hashchange'));
    });
    expect(screen.queryByRole('heading', { name: 'Watch history' })).not.toBeInTheDocument();
  } finally {
    unmount();
    window.history.replaceState(null, '', '/');
    vi.unstubAllGlobals();
  }
});
