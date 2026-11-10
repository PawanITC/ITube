async function request(path = '', options = {}) {
  const response = await fetch(`/api/liked-videos${path}`, { credentials: 'include', ...options });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    const error = new Error(response.status === 401
      ? 'Start a session using the ITube logo to manage your liked videos.'
      : problem?.detail || `Could not update liked videos (${response.status}). Please try again.`);
    error.status = response.status;
    throw error;
  }
  return response.status === 204 ? null : response.json();
}

export const listLikedVideos = (page = 0, size = 20, signal) => request(`?${new URLSearchParams({ page, size })}`, { signal });
export const getLikeStatus = (videoId, signal) => request(`/${encodeURIComponent(videoId)}`, { signal });
export const likeVideo = videoId => request(`/${encodeURIComponent(videoId)}`, { method: 'PUT' });
export const unlikeVideo = videoId => request(`/${encodeURIComponent(videoId)}`, { method: 'DELETE' });
