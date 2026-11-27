import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import App from './App';

const playlistId = '123e4567-e89b-12d3-a456-426614174000';
const first = { videoId: 25, channelId: 2, title: 'Playlist video one', channelName: 'Test channel', thumbnailUrl: '/api/videos/25/thumbnail', processingStatus: 'READY', visibility: 'PUBLIC' };
const second = { ...first, videoId: 26, title: 'Playlist video two', thumbnailUrl: '/api/videos/26/thumbnail' };
const response = (body, status = 200) => ({ ok: status < 400, status, json: async () => body });

beforeEach(() => {
  window.history.replaceState(null, '', '/');
  vi.stubGlobal('fetch', vi.fn());
});
afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

test('adds from the player, reorders, plays the queue and deletes the playlist', async () => {
  let items = [26];
  let exists = true;
  let version = 1;
  const metadata = () => ({ id: playlistId, title: 'Learning', videoCount: items.length, version: `v${version}` });
  fetch.mockImplementation(async (url, options) => {
    const path = url.split('?')[0];
    if (url === '/api/auth/me') return response({ userId: 7, channelId: 2 });
    if (url === '/api/videos') return response([first, second]);
    if (path === '/api/playlists') return response(exists ? [metadata()] : []);
    if (path === `/api/playlists/${playlistId}`) {
      if (options?.method === 'DELETE') { exists = false; return response(null, 204); }
      return response({ playlist: metadata(), videos: items.map((id, position) => ({ videoId: id, position, video: id === 25 ? first : second })), hasMore: false });
    }
    if (path === `/api/playlists/${playlistId}/videos/25/position`) {
      const body = JSON.parse(options.body);
      if (body.version !== `v${version}`) return response({ detail: 'Playlist changed' }, 409);
      items = [25, 26];
      version++;
      return response(metadata());
    }
    if (path === `/api/playlists/${playlistId}/videos/25` && options?.method === 'PUT') {
      if (!items.includes(25)) { items.push(25); version++; }
      return response(metadata());
    }
    const playback = path.match(/^\/api\/videos\/(\d+)\/playback-authorisation$/);
    if (playback) return response({ videoId: Number(playback[1]), allowed: true, sourceUrl: `/api/videos/${playback[1]}/file` });
    if (path.includes('/tiers')) return response({ owner: false, tiers: [] });
    if (path.startsWith('/api/liked-videos/')) return response({ liked: false });
    if (path.startsWith('/api/saved-videos/')) return response({ saved: false });
    return response([]);
  });
  const { container, unmount } = render(<App />);
  try {
    fireEvent.click(await screen.findByRole('link', { name: 'Play Playlist video one' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Add to playlist' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Add video' }));
    await screen.findByText('Added to Learning.');
    fireEvent.click(screen.getByRole('link', { name: 'ITube home' }));
    fireEvent.click(screen.getAllByRole('link', { name: 'Playlists' })[0]);
    fireEvent.click(await screen.findByRole('link', { name: 'Learning' }));
    expect(window.location.pathname).toBe(`/playlists/${playlistId}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Move Playlist video one up' }));
    await waitFor(() => expect(screen.getAllByRole('link', { name: /Play Playlist video/ })[0]).toHaveAccessibleName('Play Playlist video one'));
    fireEvent.click(screen.getByRole('button', { name: 'Play playlist' }));
    expect(await screen.findByRole('heading', { name: 'Playlist video one' })).toBeInTheDocument();
    expect(window.location.search).toBe(`?playlist=${playlistId}`);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Next video' })).toBeEnabled());
    await waitFor(() => expect(container.querySelector('video')).toHaveAttribute('src', '/api/videos/25/file'));
    fireEvent.ended(container.querySelector('video'));
    expect(await screen.findByRole('heading', { name: 'Playlist video two' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/watch/26');
    expect(window.location.search).toBe(`?playlist=${playlistId}`);
    fireEvent.click(screen.getByRole('button', { name: 'Back to playlist' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Delete playlist' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm delete' }));
    expect(await screen.findByRole('heading', { name: 'No playlists yet' })).toBeInTheDocument();
  } finally { unmount(); }
}, 15000);

test('reloads unlisted playlist playback outside the public feed and skips unavailable entries', async () => {
  window.history.replaceState(null, '', `/watch/25?playlist=${playlistId}`);
  const unlisted = { ...first, visibility: 'UNLISTED' };
  fetch.mockImplementation(async url => {
    if (url === '/api/auth/me') return response({ userId: 7, channelId: 2 });
    if (url.startsWith(`/api/playlists/${playlistId}?`)) return response({ playlist: { id: playlistId, title: 'Unlisted learning', videoCount: 3, version: 'v1' }, videos: [{ videoId: 25, position: 0, video: unlisted }, { videoId: 99, position: 1, video: null }, { videoId: 26, position: 2, video: second }], hasMore: false });
    const playback = url.match(/^\/api\/videos\/(\d+)\/playback-authorisation$/);
    if (playback) return response({ videoId: Number(playback[1]), allowed: true, sourceUrl: `/api/videos/${playback[1]}/file` });
    if (url.includes('/tiers')) return response({ owner: false, tiers: [] });
    if (url.startsWith('/api/liked-videos/')) return response({ liked: false });
    if (url.startsWith('/api/saved-videos/')) return response({ saved: false });
    return response([]);
  });
  const { container, unmount } = render(<App />);
  try {
    expect(await screen.findByRole('heading', { name: 'Playlist video one' })).toBeInTheDocument();
    await waitFor(() => expect(container.querySelector('video')).toHaveAttribute('src', '/api/videos/25/file'));
    expect(container.querySelector('video')).toHaveAttribute('poster', '/api/videos/25/thumbnail');
    expect(screen.getByText('Video unavailable')).toBeInTheDocument();
    fireEvent.ended(container.querySelector('video'));
    expect(await screen.findByRole('heading', { name: 'Playlist video two' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/watch/26');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Next video' })).toBeDisabled());
  } finally { unmount(); }
});

test.each(['/playlists', '/#playlists'])('opens the playlist library directly at %s', async route => {
  window.history.replaceState(null, '', route);
  fetch.mockImplementation(async url => response(url === '/api/auth/me' ? { userId: 7, channelId: 2 } : []));
  const { unmount } = render(<App />);
  try {
    expect(await screen.findByRole('heading', { name: 'No playlists yet' })).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Playlists' }).every(link => link.getAttribute('aria-current') === 'page')).toBe(true);
  } finally { unmount(); }
});
