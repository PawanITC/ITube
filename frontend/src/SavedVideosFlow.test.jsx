import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import App from './App';

beforeEach(() => {
  window.history.replaceState(null, '', '/');
  vi.stubGlobal('fetch', vi.fn());
});
afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

test('saves from the player, navigates to Watch later, replays the same video and removes it', async () => {
  let saved = false;
  const video = { videoId: 25, channelId: 2, title: 'A video for later', channelName: 'Test channel', thumbnailUrl: '/api/videos/25/thumbnail', processingStatus: 'READY', visibility: 'PUBLIC' };
  fetch.mockImplementation(async (url, options) => {
    let body = [];
    if (url === '/api/auth/me') body = { userId: 7, channelId: 2 };
    else if (url === '/api/videos') body = [video];
    else if (url === '/api/saved-videos/25') {
      if (options?.method === 'PUT' || options?.method === 'DELETE') {
        saved = options.method === 'PUT';
        return { ok: true, status: 204 };
      }
      body = { videoId: 25, saved };
    } else if (url.startsWith('/api/saved-videos?')) body = saved ? [{ videoId: 25, video, savedAt: '2026-11-22T10:00:00Z' }] : [];
    else if (url.endsWith('/playback-authorisation')) body = { videoId: 25, allowed: true, sourceUrl: '/api/videos/25/file' };
    else if (url.includes('/tiers')) body = { owner: false, tiers: [] };
    else if (url === '/api/liked-videos/25') body = { videoId: 25, liked: false };
    return { ok: true, status: 200, json: async () => body };
  });
  const { container, unmount } = render(<App />);
  try {
    fireEvent.click(await screen.findByRole('link', { name: 'Play A video for later' }));
    const save = await screen.findByRole('button', { name: 'Save video to Watch later' });
    await waitFor(() => expect(save).toBeEnabled());
    fireEvent.click(save);
    await screen.findByRole('button', { name: 'Remove video from Watch later' });
    expect(saved).toBe(true);
    fireEvent.click(screen.getByRole('link', { name: 'ITube home' }));
    fireEvent.click(screen.getAllByRole('link', { name: 'Watch later' })[0]);
    expect(await screen.findByRole('heading', { name: 'Watch later' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/saved-videos');
    const play = await screen.findByRole('link', { name: 'Play A video for later' });
    expect(play.querySelector('img')).toHaveAttribute('src', video.thumbnailUrl);
    fireEvent.click(play);
    expect(await screen.findByRole('heading', { name: video.title })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/watch/25');
    await waitFor(() => expect(container.querySelector('video')).toHaveAttribute('src', '/api/videos/25/file'));
    expect(container.querySelector('video')).toHaveAttribute('poster', video.thumbnailUrl);
    expect(await screen.findByRole('button', { name: 'Remove video from Watch later' })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByRole('link', { name: 'ITube home' }));
    fireEvent.click(screen.getAllByRole('link', { name: 'Watch later' })[0]);
    fireEvent.click(await screen.findByRole('button', { name: 'Remove A video for later from Watch later' }));
    expect(await screen.findByRole('heading', { name: 'No saved videos yet' })).toBeInTheDocument();
    expect(saved).toBe(false);
  } finally { unmount(); }
});

test.each(['/saved-videos', '/watch-later', '/#watch-later'])('opens Watch later directly at %s', async route => {
  window.history.replaceState(null, '', route);
  fetch.mockResolvedValue({ ok: true, status: 200, json: async () => [] });
  const { unmount } = render(<App />);
  try {
    expect(await screen.findByRole('heading', { name: 'No saved videos yet' })).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Watch later' }).every(link => link.getAttribute('aria-current') === 'page')).toBe(true);
    expect(fetch.mock.calls.some(([url]) => url === '/api/videos')).toBe(false);
  } finally { unmount(); }
});
