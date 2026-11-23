import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import Playlists from './Playlists';

// These tests cover manual playlist editing; the automatic card has its own API and screen-flow tests.
vi.mock('../components/Playlists/AutomaticPlaylist', () => ({ default: () => null }));

const metadata = { id: 'p1', title: 'Learning', videoCount: 2, version: 'v1' };
const entry = (id, position) => ({ videoId: id, position, addedAt: '2026-11-22T10:00:00Z', video: { videoId: id, title: `Video ${id}`, channelId: 2, channelName: 'Test channel', thumbnailUrl: `/api/videos/${id}/thumbnail` } });
const detail = (videos = [entry(25, 0), entry(26, 1)], playlist = metadata, hasMore = false) => ({ playlist, videos, page: 0, size: 20, hasMore });
const response = (body, status = 200) => ({ ok: status < 400, status, json: async () => body });
beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
afterEach(() => vi.unstubAllGlobals());

test('creates a named playlist and opens it', async () => {
  fetch.mockResolvedValueOnce(response([])).mockResolvedValueOnce(response(metadata, 201)).mockResolvedValue(response([metadata]));
  const onOpenPlaylist = vi.fn();
  render(<Playlists onOpenPlaylist={onOpenPlaylist} />);
  await screen.findByRole('heading', { name: 'No playlists yet' });
  fireEvent.change(screen.getByLabelText('Playlist name'), { target: { value: 'Learning' } });
  fireEvent.click(screen.getByRole('button', { name: 'Create playlist' }));
  await waitFor(() => expect(onOpenPlaylist).toHaveBeenCalledWith('p1'));
  expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ title: 'Learning' });
});

test('opens the exact video with its thumbnail and playlist context', async () => {
  fetch.mockResolvedValue(response(detail()));
  const onSelectVideo = vi.fn();
  render(<Playlists playlistId="p1" onSelectVideo={onSelectVideo} />);
  const play = await screen.findByRole('link', { name: 'Play Video 25' });
  expect(play.querySelector('img')).toHaveAttribute('src', '/api/videos/25/thumbnail');
  fireEvent.click(play);
  expect(onSelectVideo).toHaveBeenCalledWith(25, entry(25, 0).video, 'p1');
});

test('renames a playlist and reloads its metadata', async () => {
  fetch.mockResolvedValueOnce(response(detail())).mockResolvedValueOnce(response({ ...metadata, title: 'New name' }))
    .mockResolvedValueOnce(response(detail(undefined, { ...metadata, title: 'New name' })));
  render(<Playlists playlistId="p1" />);
  await screen.findByRole('heading', { name: 'Learning' });
  fireEvent.change(screen.getByLabelText('Playlist name'), { target: { value: 'New name' } });
  fireEvent.click(screen.getByRole('button', { name: 'Rename playlist' }));
  expect(await screen.findByRole('heading', { name: 'New name' })).toBeInTheDocument();
  expect(fetch.mock.calls[1][1].method).toBe('PATCH');
});

test('reorders using the displayed version, then renders the server order', async () => {
  const reordered = [entry(26, 0), entry(25, 1)];
  fetch.mockResolvedValueOnce(response(detail())).mockResolvedValueOnce(response({ ...metadata, version: 'v2' }))
    .mockResolvedValueOnce(response(detail(reordered, { ...metadata, version: 'v2' })));
  render(<Playlists playlistId="p1" />);
  fireEvent.click(await screen.findByRole('button', { name: 'Move Video 26 up' }));
  await waitFor(() => expect(screen.getAllByRole('link', { name: /Play Video/ })[0]).toHaveAccessibleName('Play Video 26'));
  expect(fetch.mock.calls[1][0]).toBe('/api/playlists/p1/videos/26/position');
  expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ position: 0, version: 'v1' });
});

test('conflicting edits show an error and retry refreshes the version', async () => {
  fetch.mockResolvedValueOnce(response(detail())).mockResolvedValueOnce(response({ detail: 'Playlist changed. Refresh it and try again.' }, 409))
    .mockResolvedValueOnce(response(detail(undefined, { ...metadata, version: 'v2' })));
  render(<Playlists playlistId="p1" />);
  fireEvent.click(await screen.findByRole('button', { name: 'Move Video 26 up' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Playlist changed');
  expect(screen.queryByText('Playlist order updated.')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(3));
  await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
});

test('unavailable entries can be removed and the playlist becomes empty', async () => {
  fetch.mockResolvedValueOnce(response(detail([{ ...entry(25, 0), video: null }], { ...metadata, videoCount: 1 })))
    .mockResolvedValueOnce(response({ ...metadata, videoCount: 0 }))
    .mockResolvedValueOnce(response(detail([], { ...metadata, videoCount: 0 })));
  render(<Playlists playlistId="p1" />);
  fireEvent.click(await screen.findByRole('button', { name: 'Remove unavailable video 1 from playlist' }));
  expect(await screen.findByRole('heading', { name: 'This playlist is empty' })).toBeInTheDocument();
  expect(fetch.mock.calls[1][0]).toBe('/api/playlists/p1/videos/25');
  expect(fetch.mock.calls[1][1].method).toBe('DELETE');
});

test('delete requires confirmation and cancel sends no request', async () => {
  fetch.mockResolvedValueOnce(response(detail())).mockResolvedValueOnce(response(null, 204)).mockResolvedValue(response(detail()));
  const onOpenPlaylist = vi.fn();
  render(<Playlists playlistId="p1" onOpenPlaylist={onOpenPlaylist} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Delete playlist' }));
  expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(fetch).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: 'Delete playlist' }));
  fireEvent.click(screen.getByRole('button', { name: 'Confirm delete' }));
  await waitFor(() => expect(onOpenPlaylist).toHaveBeenCalledWith());
  expect(fetch.mock.calls[1][1].method).toBe('DELETE');
});

test('play playlist chooses the first available entry, skipping unavailable videos', async () => {
  const videos = [{ ...entry(24, 0), video: null }, entry(25, 1)];
  fetch.mockResolvedValue(response(detail(videos)));
  const onSelectVideo = vi.fn();
  render(<Playlists playlistId="p1" onSelectVideo={onSelectVideo} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Play playlist' }));
  await waitFor(() => expect(onSelectVideo).toHaveBeenCalledWith(25, entry(25, 1).video, 'p1'));
  expect(fetch.mock.calls[1][0]).toBe('/api/playlists/p1?page=0&size=100');
});

test('aborts list requests when navigating away', () => {
  fetch.mockImplementation(() => new Promise(() => {}));
  const { unmount } = render(<Playlists />);
  const signal = fetch.mock.calls[0][1].signal;
  unmount();
  expect(signal.aborted).toBe(true);
});

test('automatic mixes open through the snapshot API and cannot be edited as manual playlists', async () => {
  fetch.mockResolvedValue(response(detail(undefined, { ...metadata, title: 'For you' })));
  render(<Playlists playlistId="automatic:p1" />);
  await screen.findByRole('heading', { name: 'For you' });
  expect(fetch.mock.calls[0][0]).toBe('/api/playlists/automatic/p1?page=0&size=20');
  expect(screen.queryByRole('button', { name: 'Rename playlist' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Delete playlist' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Move Video 26 up' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Remove Video 25 from playlist' })).not.toBeInTheDocument();
});
