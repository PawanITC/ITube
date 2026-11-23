import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import AddToPlaylistButton from './AddToPlaylistButton';

const response = (body, status = 200) => ({ ok: status < 400, status, json: async () => body });
beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
afterEach(() => vi.unstubAllGlobals());

test('adds a video to the selected existing playlist', async () => {
  fetch.mockResolvedValueOnce(response([{ id: 'p1', title: 'Learning' }])).mockResolvedValueOnce(response({ videoCount: 1 }));
  render(<AddToPlaylistButton videoId={25} />);
  expect(fetch).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: 'Add to playlist' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Add video' }));
  expect(await screen.findByText('Added to Learning.')).toBeInTheDocument();
  expect(fetch.mock.calls[1]).toEqual(['/api/playlists/p1/videos/25', expect.objectContaining({ method: 'PUT', credentials: 'include' })]);
});

test('creates a playlist and adds the current video', async () => {
  fetch.mockResolvedValueOnce(response([])).mockResolvedValueOnce(response({ id: 'p2', title: 'Spring Boot' }, 201)).mockResolvedValueOnce(response({ videoCount: 1 }));
  render(<AddToPlaylistButton videoId={25} />);
  fireEvent.click(screen.getByRole('button', { name: 'Add to playlist' }));
  fireEvent.change(await screen.findByLabelText('New playlist name'), { target: { value: 'Spring Boot' } });
  fireEvent.click(screen.getByRole('button', { name: 'Create and add' }));
  expect(await screen.findByText('Added to Spring Boot.')).toBeInTheDocument();
  expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ title: 'Spring Boot' });
  expect(fetch.mock.calls[2][0]).toBe('/api/playlists/p2/videos/25');
});

test('a failed add can be retried without creating the playlist again', async () => {
  fetch.mockResolvedValueOnce(response([])).mockResolvedValueOnce(response({ id: 'p2', title: 'Spring' }, 201))
    .mockResolvedValueOnce(response({}, 503)).mockResolvedValueOnce(response([{ id: 'p2', title: 'Spring' }]))
    .mockResolvedValueOnce(response({ videoCount: 1 }));
  render(<AddToPlaylistButton videoId={25} />);
  fireEvent.click(screen.getByRole('button', { name: 'Add to playlist' }));
  fireEvent.change(await screen.findByLabelText('New playlist name'), { target: { value: 'Spring' } });
  fireEvent.click(screen.getByRole('button', { name: 'Create and add' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('503');
  expect(screen.queryByText('Added to Spring.')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Add video' }));
  expect(await screen.findByText('Added to Spring.')).toBeInTheDocument();
  expect(fetch.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1);
});

test('asks anonymous viewers to sign in and aborts stale account requests', async () => {
  let finish;
  fetch.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; })).mockResolvedValueOnce(response({}, 401));
  const { rerender } = render(<AddToPlaylistButton key="user1" videoId={25} />);
  fireEvent.click(screen.getByRole('button', { name: 'Add to playlist' }));
  const signal = fetch.mock.calls[0][1].signal;
  rerender(<AddToPlaylistButton key="user2" videoId={25} />);
  fireEvent.click(screen.getByRole('button', { name: 'Add to playlist' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Sign in');
  await act(async () => finish(response([{ id: 'p1', title: 'Old user data' }])));
  expect(signal.aborted).toBe(true);
  expect(screen.queryByText('Old user data')).not.toBeInTheDocument();
});

test('rapid repeated submissions send one add request', async () => {
  let finish;
  fetch.mockResolvedValueOnce(response([{ id: 'p1', title: 'Learning' }])).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  render(<AddToPlaylistButton videoId={25} />);
  fireEvent.click(screen.getByRole('button', { name: 'Add to playlist' }));
  const add = await screen.findByRole('button', { name: 'Add video' });
  fireEvent.click(add);
  fireEvent.click(add);
  expect(fetch).toHaveBeenCalledTimes(2);
  await act(async () => finish(response({ videoCount: 1 })));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Add to playlist' })).toHaveAttribute('aria-expanded', 'false'));
});
