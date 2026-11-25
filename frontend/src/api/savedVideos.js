import { engagementFetch } from './engagement';

async function request(path = '', options = {}) {
  const response = await engagementFetch(`/api/saved-videos${path}`, { credentials: 'include', ...options });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    const error = new Error(response.status === 401
      ? 'Sign in to save videos and manage your Watch later list.'
      : problem?.detail || `Could not update saved videos (${response.status}). Please try again.`);
    error.status = response.status;
    throw error;
  }
  return response.status === 204 ? null : response.json();
}

export const listSavedVideos = (page = 0, size = 20, signal) => request(`?${new URLSearchParams({ page, size })}`, { signal });
export const getSavedStatus = (videoId, signal) => request(`/${encodeURIComponent(videoId)}`, { signal });
export const saveVideo = videoId => request(`/${encodeURIComponent(videoId)}`, { method: 'PUT' });
export const removeSavedVideo = videoId => request(`/${encodeURIComponent(videoId)}`, { method: 'DELETE' });
export const clearSavedVideos = () => request('', { method: 'DELETE' });
