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
    if (url === '/api/session/current' || url === '/api/auth/me') body = { userId: 7, channelId: 2 };
    else if (url.startsWith('/api/history?')) body = [{ id: 1, videoId: 25, watchedAt: '2026-11-10T10:00:00Z', video }];
    else if (url === '/api/history' && options?.method === 'POST') return { ok: true, status: 201, json: async () => ({ id: 2, videoId: 25 }) };
    else if (url.endsWith('/playback-authorisation')) body = { videoId: 25, allowed: true, sourceUrl: '/api/videos/25/file' };
    else if (url.includes('/tiers')) body = { owner: false, tiers: [] };
    else if (url === '/api/liked-videos/25') body = { videoId: 25, liked: false };
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

test('navigates to liked videos and plays a liked unlisted video outside the public feed', async () => {
  const video = { videoId: 25, channelId: 2, title: 'An unlisted favourite', channelName: 'Test channel', duration: '00:01:00', processingStatus: 'READY', visibility: 'UNLISTED' };
  fetch.mockImplementation(async url => {
    let body = [];
    if (url === '/api/auth/me') body = { userId: 7, channelId: 2 };
    else if (url.startsWith('/api/liked-videos?')) body = [{ video, likedAt: '2026-11-10T10:00:00Z' }];
    else if (url === '/api/liked-videos/25') body = { videoId: 25, liked: true };
    else if (url.endsWith('/playback-authorisation')) body = { allowed: true, sourceUrl: '/api/videos/25/file' };
    else if (url.includes('/tiers')) body = { owner: false, tiers: [] };
    return { ok: true, status: 200, json: async () => body };
  });
  const { unmount } = render(<App />);
  try {
    fireEvent.click(screen.getAllByRole('link', { name: 'Liked videos' })[0]);
    expect(await screen.findByRole('heading', { name: 'Liked videos' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/liked-videos');
    expect(screen.getAllByRole('link', { name: 'Liked videos' }).every(link => link.getAttribute('aria-current') === 'page')).toBe(true);
    fireEvent.click(await screen.findByRole('link', { name: 'Play An unlisted favourite' }));
    expect(await screen.findByRole('heading', { name: 'An unlisted favourite' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Unlike video' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/watch/25');
  } finally {
    unmount();
    window.history.replaceState(null, '', '/');
  }
});

test('opens History through hash navigation and returns home', async () => {
  vi.stubGlobal('fetch', vi.fn(async url => ({ ok: true, status: 200, json: async () => url === '/api/auth/me' ? { userId: 7, channelId: 2 } : [] })));
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

test.each([['/history', 'Watch history'], ['/liked-videos', 'Liked videos'], ['/playlists', 'Playlists'], ['/saved-videos', 'Watch later']])('signed-out visitors to %s keep the page and Sign In goes to login', async (route, heading) => {
  window.history.replaceState(null, '', route);
  vi.stubGlobal('fetch', vi.fn(async url => url === '/api/auth/me' ? { ok: false, status: 401, json: async () => ({}) } : { ok: true, status: 200, json: async () => [] }));
  const { unmount } = render(<App />);
  try {
    expect(await screen.findByRole('alert')).toHaveTextContent('Sign in to use this feature');
    expect(screen.getByRole('heading', { level: 1, name: heading })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Refresh' })).toBeNull();
    expect(fetch.mock.calls.some(([url]) => /^\/api\/(history|liked-videos|playlists|saved-videos)/.test(url))).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Sign In' }));
    expect(window.location.pathname).toBe('/login');
  } finally {
    unmount();
    window.history.replaceState(null, '', '/');
    vi.unstubAllGlobals();
  }
});
