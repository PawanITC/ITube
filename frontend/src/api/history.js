async function request(path = '', { page, size, signal, method = 'GET', body } = {}) {
  const query = new URLSearchParams();
  if (page !== undefined) query.set('page', page);
  if (size !== undefined) query.set('size', size);
  // Identity comes from the existing session cookie, never a hardcoded user ID.
  const response = await fetch(`/api/history${path}${query.size ? `?${query}` : ''}`, {
    method, signal, credentials: 'include',
    ...(body ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : {}),
  });
  // A missing entry is already removed; refresh the list after this result.
  if (method === 'DELETE' && path && response.status === 404) return;
  if (!response.ok) {
    const error = new Error(response.status === 401
      ? 'Start a session using the ITube logo to view your watch history.'
      : `History request failed (${response.status}). Please try again.`);
    error.status = response.status;
    throw error;
  }
  return response.status === 204 ? undefined : response.json();
}

export const getHistory = (page, size, signal) => request('', { page, size, signal });
export const removeHistory = id => request(`/${encodeURIComponent(id)}`, { method: 'DELETE' });
export const clearHistory = () => request('', { method: 'DELETE' });
// Called by the player's playing event, not by page rendering or thumbnail clicks.
export const recordWatch = videoId => request('', { method: 'POST', body: { videoId } });
