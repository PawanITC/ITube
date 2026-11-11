import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, test, expect, vi } from 'vitest';
import LikeButton from './LikeButton';

const response = (body, status = 200) => ({ ok: status < 400, status, json: async () => body });
beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
afterEach(() => vi.unstubAllGlobals());

test('loads current status then likes and unlikes through the API', async () => {
  fetch.mockResolvedValueOnce(response({ liked: false })).mockResolvedValueOnce(response(null, 204)).mockResolvedValueOnce(response(null, 204));
  render(<LikeButton videoId={25} />);
  const like = await screen.findByRole('button', { name: 'Like video' });
  await waitFor(() => expect(like).toBeEnabled());
  fireEvent.click(like);
  const unlike = await screen.findByRole('button', { name: 'Unlike video' });
  expect(unlike).toHaveAttribute('aria-pressed', 'true');
  expect(fetch.mock.calls[1][0]).toBe('/api/liked-videos/25');
  expect(fetch.mock.calls[1][1]).toMatchObject({ method: 'PUT', credentials: 'include' });
  fireEvent.click(unlike);
  await waitFor(() => expect(screen.getByRole('button', { name: 'Like video' })).toHaveAttribute('aria-pressed', 'false'));
  expect(fetch.mock.calls[2][1].method).toBe('DELETE');
});

test('a failed save keeps the old status and retry reloads the server state', async () => {
  fetch.mockResolvedValueOnce(response({ liked: false })).mockResolvedValueOnce(response({}, 500)).mockResolvedValueOnce(response({ liked: false }));
  render(<LikeButton videoId={25} />);
  await waitFor(() => expect(screen.getByRole('button', { name: 'Like video' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: 'Like video' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('500');
  expect(screen.getByRole('button', { name: 'Like video' })).toHaveAttribute('aria-pressed', 'false');
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Like video' })).toBeEnabled());
});

test('requires a session instead of using a hardcoded user ID', async () => {
  fetch.mockResolvedValueOnce(response({}, 401));
  render(<LikeButton videoId={25} />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Start a session');
  expect(screen.getByRole('button', { name: 'Like video' })).toBeDisabled();
});

test('prevents repeat requests while saving', async () => {
  let resolve;
  fetch.mockResolvedValueOnce(response({ liked: false })).mockImplementationOnce(() => new Promise(done => { resolve = done; }));
  render(<LikeButton videoId={25} />);
  const button = screen.getByRole('button', { name: 'Like video' });
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
  fireEvent.click(button);
  expect(fetch).toHaveBeenCalledTimes(2);
  await act(async () => resolve(response(null, 204)));
  expect(screen.getByRole('button', { name: 'Unlike video' })).toBeEnabled();
});

test('ignores a stale load after navigating to another video', async () => {
  let resolveOld;
  fetch.mockImplementationOnce(() => new Promise(done => { resolveOld = done; })).mockResolvedValueOnce(response({ liked: true }));
  const { rerender } = render(<LikeButton key="25" videoId={25} />);
  const oldSignal = fetch.mock.calls[0][1].signal;
  rerender(<LikeButton key="26" videoId={26} />);
  await screen.findByRole('button', { name: 'Unlike video' });
  await act(async () => resolveOld(response({ liked: false })));
  expect(oldSignal.aborted).toBe(true);
  expect(screen.getByRole('button', { name: 'Unlike video' })).toHaveAttribute('aria-pressed', 'true');
});
