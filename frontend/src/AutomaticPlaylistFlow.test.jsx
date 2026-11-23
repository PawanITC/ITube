import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import App from './App';

const id = '123e4567-e89b-42d3-a456-426614174000';
const first = { videoId: 25, channelId: 2, title: 'Recommended Java', channelName: 'Code channel', thumbnailUrl: '/api/videos/25/thumbnail', processingStatus: 'READY', visibility: 'PUBLIC' };
const second = { ...first, videoId: 26, title: 'Recommended Spring', thumbnailUrl: '/api/videos/26/thumbnail' };
const mix = { playlist: { id, title: 'For you', videoCount: 2, version: id }, videos: [{ videoId: 25, position: 0, video: first }, { videoId: 26, position: 1, video: second }], hasMore: false, description: 'Based on your recent watches and likes.' };
const response = body => ({ ok: true, status: 200, json: async () => body });

beforeEach(() => {
  window.history.replaceState(null, '', '/playlists');
  vi.stubGlobal('fetch', vi.fn(async url => {
    if (url === '/api/auth/me') return response({ userId: 7, channelId: 2 });
    if (url.startsWith('/api/playlists/automatic')) return response(mix);
    const playback = url.match(/^\/api\/videos\/(\d+)\/playback-authorisation$/);
    if (playback) return response({ videoId: Number(playback[1]), allowed: true, sourceUrl: `/api/videos/${playback[1]}/file` });
    if (url.includes('/tiers')) return response({ owner: false, tiers: [] });
    if (url.startsWith('/api/liked-videos/')) return response({ liked: false });
    if (url.startsWith('/api/saved-videos/')) return response({ saved: false });
    return response([]);
  }));
});
afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState(null, '', '/'); });

test('automatically shows a mix, preserves its snapshot through ordered playback and returns to a read-only view', async () => {
  const { container, unmount } = render(<App />);
  try {
    fireEvent.click(await screen.findByRole('button', { name: 'Play your mix' }));
    expect(await screen.findByRole('heading', { name: first.title })).toBeInTheDocument();
    expect(new URLSearchParams(window.location.search).get('playlist')).toBe(`automatic:${id}`);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Next video' })).toBeEnabled());
    await waitFor(() => expect(container.querySelector('video')).toHaveAttribute('src', '/api/videos/25/file'));
    fireEvent.ended(container.querySelector('video'));
    expect(await screen.findByRole('heading', { name: second.title })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/watch/26');
    fireEvent.click(screen.getByRole('button', { name: 'Back to playlist' }));
    expect(await screen.findByRole('heading', { name: 'For you' })).toBeInTheDocument();
    expect(window.location.pathname).toBe(`/playlists/automatic/${id}`);
    expect(screen.queryByRole('button', { name: 'Delete playlist' })).not.toBeInTheDocument();
    expect(fetch.mock.calls.some(([url, options]) => url === '/api/playlists' && options?.method === 'POST')).toBe(false);
  } finally { unmount(); }
});

test('a direct automatic snapshot URL survives a page reload', async () => {
  window.history.replaceState(null, '', `/playlists/automatic/${id}`);
  const { unmount } = render(<App />);
  try {
    expect(await screen.findByRole('heading', { name: 'For you' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'Play Recommended Java' })).toBeInTheDocument();
    expect(fetch.mock.calls.some(([url]) => url.startsWith(`/api/playlists/automatic/${id}?`))).toBe(true);
  } finally { unmount(); }
});
