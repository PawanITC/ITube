import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, afterEach, expect, test, vi } from 'vitest';
import LikedVideos from './LikedVideos';

const entry = id => ({ video: { videoId: id, channelId: 2, title: `Video ${id}`, channelName: 'Test channel', duration: '00:01:00', visibility: 'PUBLIC' }, likedAt: '2026-11-10T10:00:00Z' });
const response = (body, status = 200) => ({ ok: status < 400, status, json: async () => body });
beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
afterEach(() => vi.unstubAllGlobals());

test('loads real video cards and opens the selected video with its metadata', async () => {
  fetch.mockResolvedValueOnce(response([entry(25)]));
  const onSelectVideo = vi.fn();
  render(<LikedVideos onSelectVideo={onSelectVideo} />);
  fireEvent.click(await screen.findByRole('link', { name: 'Play Video 25' }));
  expect(onSelectVideo).toHaveBeenCalledWith(25, entry(25).video);
  expect(fetch.mock.calls[0][0]).toBe('/api/liked-videos?page=0&size=20');
  expect(fetch.mock.calls[0][1].credentials).toBe('include');
});

test('unlikes and reloads the list', async () => {
  fetch.mockResolvedValueOnce(response([entry(25)])).mockResolvedValueOnce(response(null, 204)).mockResolvedValueOnce(response([]));
  render(<LikedVideos />);
  fireEvent.click(await screen.findByRole('button', { name: 'Unlike Video 25' }));
  expect(await screen.findByRole('heading', { name: 'No liked videos yet' })).toBeInTheDocument();
  expect(fetch.mock.calls[1][0]).toBe('/api/liked-videos/25');
  expect(fetch.mock.calls[1][1].method).toBe('DELETE');
});

test('shows a session prompt and can retry after sign-in', async () => {
  fetch.mockResolvedValueOnce(response({}, 401)).mockResolvedValueOnce(response([]));
  render(<LikedVideos />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Start a session');
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
  expect(await screen.findByRole('heading', { name: 'No liked videos yet' })).toBeInTheDocument();
});

test('failure to unlike is shown without announcing success', async () => {
  fetch.mockResolvedValueOnce(response([entry(25)])).mockResolvedValueOnce(response({}, 500));
  render(<LikedVideos />);
  fireEvent.click(await screen.findByRole('button', { name: 'Unlike Video 25' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('500');
  expect(screen.queryByText('Removed Video 25 from liked videos.')).not.toBeInTheDocument();
});

test('pages forward and back with bounded API requests', async () => {
  const full = Array.from({ length: 20 }, (_, index) => entry(index + 1));
  fetch.mockResolvedValueOnce(response(full)).mockResolvedValueOnce(response([entry(21)])).mockResolvedValueOnce(response(full));
  render(<LikedVideos />);
  await screen.findByRole('link', { name: 'Play Video 1' });
  expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: 'Next' }));
  await screen.findByRole('link', { name: 'Play Video 21' });
  expect(screen.getByText('Page 2')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  expect(fetch.mock.calls[1][0]).toContain('page=1');
  fireEvent.click(screen.getByRole('button', { name: 'Previous' }));
  await screen.findByRole('link', { name: 'Play Video 1' });
  expect(screen.getByText('Page 1')).toBeInTheDocument();
});

test('deleting the last entry on a later page returns to the previous page', async () => {
  const full = Array.from({ length: 20 }, (_, index) => entry(index + 1));
  fetch.mockResolvedValueOnce(response(full)).mockResolvedValueOnce(response([entry(21)]))
    .mockResolvedValueOnce(response(null, 204)).mockResolvedValueOnce(response([])).mockResolvedValueOnce(response(full));
  render(<LikedVideos />);
  await screen.findByRole('link', { name: 'Play Video 1' });
  fireEvent.click(screen.getByRole('button', { name: 'Next' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Unlike Video 21' }));
  await screen.findByRole('link', { name: 'Play Video 1' });
  expect(screen.getByText('Page 1')).toBeInTheDocument();
});

test('aborts requests on unmount', () => {
  fetch.mockImplementation(() => new Promise(() => {}));
  const { unmount } = render(<LikedVideos />);
  const signal = fetch.mock.calls[0][1].signal;
  unmount();
  expect(signal.aborted).toBe(true);
});
