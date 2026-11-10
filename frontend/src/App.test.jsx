import { act, fireEvent, render, screen } from '@testing-library/react';
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

test('navigates to liked videos and plays a liked unlisted video outside the public feed', async () => {
  const video = { videoId: 25, channelId: 2, title: 'An unlisted favourite', channelName: 'Test channel', duration: '00:01:00', processingStatus: 'READY', visibility: 'UNLISTED' };
  fetch.mockImplementation(async url => {
    let body = [];
    if (url.startsWith('/api/liked-videos?')) body = [{ video, likedAt: '2026-11-10T10:00:00Z' }];
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
