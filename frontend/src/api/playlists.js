import { engagementFetch } from './engagement';

async function request(path = '', options = {}) {
  const response = await engagementFetch(`/api/playlists${path}`, { credentials: 'include', ...options });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    const error = new Error(response.status === 401 ? 'Sign in to create and manage your playlists.'
      : problem?.detail || `Could not update playlists (${response.status}). Please try again.`);
    error.status = response.status;
    throw error;
  }
  return response.status === 204 ? null : response.json();
}

const json = (method, body) => ({ method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
const idPath = id => `/${encodeURIComponent(id)}`;
const playbackPath = id => id.startsWith('automatic:') ? `/automatic/${encodeURIComponent(id.slice('automatic:'.length))}` : idPath(id);
export const listPlaylists = (page = 0, size = 20, signal) => request(`?${new URLSearchParams({ page, size })}`, { signal });
export const getPlaylist = (id, page = 0, size = 20, signal) => request(`${playbackPath(id)}?${new URLSearchParams({ page, size })}`, { signal });
export const getAutomaticPlaylist = (refresh = false, signal) => request(refresh ? '/automatic/refresh' : '/automatic', { method: refresh ? 'POST' : 'GET', signal });
export const createPlaylist = title => request('', json('POST', { title }));
export const renamePlaylist = (id, title) => request(idPath(id), json('PATCH', { title }));
export const deletePlaylist = id => request(idPath(id), { method: 'DELETE' });
export const addPlaylistVideo = (id, videoId) => request(`${idPath(id)}/videos/${encodeURIComponent(videoId)}`, { method: 'PUT' });
export const removePlaylistVideo = (id, videoId) => request(`${idPath(id)}/videos/${encodeURIComponent(videoId)}`, { method: 'DELETE' });
export const movePlaylistVideo = (id, videoId, position, version) => request(`${idPath(id)}/videos/${encodeURIComponent(videoId)}/position`, json('PUT', { position, version }));

// Playlists are capped at 500 videos. Fetch at most five pages, using one consistent version.
export async function loadPlaylistQueue(id, signal) {
  const first = await getPlaylist(id, 0, 100, signal);
  const videos = [...first.videos];
  let current = first;
  for (let page = 1; current.hasMore; page++) {
    if (page >= 5) throw new Error('This playlist is too large. Refresh it and try again.');
    current = await getPlaylist(id, page, 100, signal);
    if (current.playlist.version !== first.playlist.version) throw new Error('Playlist changed. Refresh it and try again.');
    videos.push(...current.videos);
  }
  return { playlist: first.playlist, videos };
}
