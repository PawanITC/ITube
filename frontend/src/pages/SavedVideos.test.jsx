import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import SavedVideos from './SavedVideos';

const entry = id => ({ videoId: id, savedAt: '2026-11-22T10:00:00Z', video: { videoId: id, channelId: 2, title: `Video ${id}`, channelName: 'Test channel', thumbnailUrl: `/api/videos/${id}/thumbnail`, duration: '00:01:00', visibility: 'PUBLIC' } });
const response = (body, status = 200) => ({ ok: status < 400, status, json: async () => body });
beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
afterEach(() => vi.unstubAllGlobals());

test('loads real thumbnails and opens the selected saved video with its metadata', async () => {
  fetch.mockResolvedValueOnce(response([entry(25)]));
  const onSelectVideo = vi.fn();
  render(<SavedVideos onSelectVideo={onSelectVideo} />);
  const play = await screen.findByRole('link', { name: 'Play Video 25' });
  expect(play.querySelector('img')).toHaveAttribute('src', '/api/videos/25/thumbnail');
  fireEvent.click(play);
  expect(onSelectVideo).toHaveBeenCalledWith(25, entry(25).video);
  expect(fetch.mock.calls[0]).toEqual(['/api/saved-videos?page=0&size=20', expect.objectContaining({ credentials: 'include' })]);
});

test('removes unavailable videos by id without needing video metadata', async () => {
  fetch.mockResolvedValueOnce(response([{ ...entry(25), video: null }])).mockResolvedValueOnce(response(null, 204)).mockResolvedValueOnce(response([]));
  render(<SavedVideos />);
  expect(await screen.findByRole('heading', { name: 'Video unavailable' })).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: /Play/ })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Remove unavailable video from Watch later' }));
  expect(await screen.findByRole('heading', { name: 'No saved videos yet' })).toBeInTheDocument();
  expect(fetch.mock.calls[1][0]).toBe('/api/saved-videos/25');
  expect(fetch.mock.calls[1][1].method).toBe('DELETE');
});

test('clear requires confirmation and cancellation makes no request', async () => {
  fetch.mockResolvedValueOnce(response([entry(25)])).mockResolvedValueOnce(response(null, 204)).mockResolvedValueOnce(response([]));
  render(<SavedVideos />);
  await screen.findByRole('link', { name: 'Play Video 25' });
  fireEvent.click(screen.getByRole('button', { name: 'Clear saved videos' }));
  expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(fetch).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: 'Clear saved videos' }));
  fireEvent.click(screen.getByRole('button', { name: 'Confirm clear' }));
  expect(await screen.findByRole('heading', { name: 'No saved videos yet' })).toBeInTheDocument();
  expect(fetch.mock.calls[1][0]).toBe('/api/saved-videos');
  expect(fetch.mock.calls[1][1].method).toBe('DELETE');
});

test('last removal on a later page returns to the previous page', async () => {
  const full = Array.from({ length: 20 }, (_, index) => entry(index + 1));
  fetch.mockResolvedValueOnce(response(full)).mockResolvedValueOnce(response([entry(21)]))
    .mockResolvedValueOnce(response(null, 204)).mockResolvedValueOnce(response([])).mockResolvedValueOnce(response(full));
  render(<SavedVideos />);
  await screen.findByRole('link', { name: 'Play Video 1' });
  fireEvent.click(screen.getByRole('button', { name: 'Next' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Remove Video 21 from Watch later' }));
  await screen.findByRole('link', { name: 'Play Video 1' });
  expect(screen.getByText('Page 1')).toBeInTheDocument();
  expect(fetch.mock.calls[1][0]).toContain('page=1');
});

test('load failures show a retry and empty state after recovery', async () => {
  fetch.mockResolvedValueOnce(response({}, 401)).mockResolvedValueOnce(response([]));
  render(<SavedVideos />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Sign in');
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
  expect(await screen.findByRole('heading', { name: 'No saved videos yet' })).toBeInTheDocument();
});

test('failed removal does not report success and the saved entry remains after retry', async () => {
  fetch.mockResolvedValueOnce(response([entry(25)])).mockResolvedValueOnce(response({}, 500)).mockResolvedValueOnce(response([entry(25)]));
  render(<SavedVideos />);
  fireEvent.click(await screen.findByRole('button', { name: 'Remove Video 25 from Watch later' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('500');
  expect(screen.queryByText('Video removed from Watch later.')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
  expect(await screen.findByRole('link', { name: 'Play Video 25' })).toBeInTheDocument();
});

test('unmounting aborts the previous accounts list request', () => {
  fetch.mockImplementation(() => new Promise(() => {}));
  const { unmount } = render(<SavedVideos />);
  const signal = fetch.mock.calls[0][1].signal;
  unmount();
  expect(signal.aborted).toBe(true);
});
