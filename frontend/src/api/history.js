// Replace this development identity with authenticated requests when auth is ready.
const userId = import.meta.env.VITE_HISTORY_USER_ID || 'user-123';

async function request(path = '', { page, size, signal, method = 'GET', body } = {}) {
  const query = new URLSearchParams({ userId });
  if (page !== undefined) query.set('page', page);
  if (size !== undefined) query.set('size', size);
  const response = await fetch(`/api/history${path}?${query}`, {
    method, signal,
    ...(body ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : {}),
  });
  // A missing entry is already removed; refresh the list after this result.
  if (method === 'DELETE' && path && response.status === 404) return;
  if (!response.ok) throw new Error(`History request failed (${response.status}). Please try again.`);
  return response.status === 204 ? undefined : response.json();
}

export const getHistory = (page, size, signal) => request('', { page, size, signal });
export const removeHistory = id => request(`/${encodeURIComponent(id)}`, { method: 'DELETE' });
export const clearHistory = () => request('', { method: 'DELETE' });
// Call when actual video playback begins, once the player has real backend IDs.
export const recordWatch = videoId => request('', { method: 'POST', body: { videoId } });
