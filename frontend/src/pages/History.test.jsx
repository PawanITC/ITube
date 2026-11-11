import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import History from './History';

const entry = (id = 1) => ({ id, videoId: 25 + id, watchedAt: '2026-10-28T10:00:00Z',
  video: { videoId: 25 + id, channelId: 2, title: `Video ${25 + id}`, channelName: 'Test channel', thumbnailUrl: `/api/videos/${25 + id}/thumbnail`, visibility: 'PUBLIC' } });
const response = (body, status = 200) => ({ ok: status < 400, status, json: async () => body });

beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
afterEach(() => vi.unstubAllGlobals());

test('uses the actual video thumbnail and opens the same video from thumbnail or title', async () => {
  fetch.mockResolvedValueOnce(response([entry()]));
  const onSelectVideo = vi.fn();
  render(<History onSelectVideo={onSelectVideo} />);
  expect(await screen.findByRole('img', { name: 'Thumbnail for Video 26' })).toHaveAttribute('src', '/api/videos/26/thumbnail');
  const thumbnail = screen.getByRole('link', { name: 'Play Video 26' });
  expect(thumbnail).toHaveAttribute('href', '/watch/26');
  fireEvent.click(thumbnail);
  fireEvent.click(screen.getByRole('link', { name: 'Video 26' }));
  expect(onSelectVideo).toHaveBeenCalledTimes(2);
  expect(onSelectVideo).toHaveBeenLastCalledWith(26, entry().video);
});

test('videos without thumbnails use the same placeholder as the feed', async () => {
  const item = entry();
  item.video.thumbnailUrl = null;
  fetch.mockResolvedValueOnce(response([item]));
  render(<History />);
  expect(await screen.findByRole('img', { name: 'Thumbnail for Video 26' })).toHaveAttribute('src', '/thumbnail-placeholder.svg');
});

test('missing or inaccessible videos have no playback link but can still be removed', async () => {
  fetch.mockResolvedValueOnce(response([{ ...entry(), video: null }]));
  render(<History />);
  await screen.findByRole('heading', { name: 'Video 26' });
  expect(screen.getByText('This video is unavailable or private.')).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: /Video 26/ })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Remove video 26 from history' })).toBeEnabled();
});

test('loads history from the API and removes an entry by history ID', async () => {
  fetch.mockResolvedValueOnce(response([entry()])).mockResolvedValueOnce(response(undefined, 204)).mockResolvedValueOnce(response([]));
  render(<History />);
  expect(screen.getByText('Loading your history…')).toBeInTheDocument();
  expect(await screen.findByRole('heading', { name: 'Video 26' })).toBeInTheDocument();
  expect(fetch.mock.calls[0][0]).toContain('/api/history?page=0&size=20');
  fireEvent.click(screen.getByRole('button', { name: 'Remove video 26 from history' }));
  expect(await screen.findByText('Your story starts here')).toBeInTheDocument();
  expect(fetch.mock.calls[1][0]).toBe('/api/history/1');
  expect(fetch.mock.calls[1][1].method).toBe('DELETE');
});

test('asks for confirmation before clearing history and allows cancellation', async () => {
  fetch.mockResolvedValueOnce(response([entry()])).mockResolvedValueOnce(response(undefined, 204)).mockResolvedValueOnce(response([]));
  render(<History />);
  await screen.findByRole('heading', { name: 'Video 26' });
  fireEvent.click(screen.getByRole('button', { name: 'Clear all history' }));
  expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(fetch).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: 'Clear all history' }));
  fireEvent.click(screen.getByRole('button', { name: 'Confirm clear all' }));
  expect(await screen.findByText('Your story starts here')).toBeInTheDocument();
  expect(fetch.mock.calls[1][0]).toBe('/api/history');
  expect(fetch.mock.calls[1][1].method).toBe('DELETE');
});

test('shows load failures and can retry', async () => {
  fetch.mockRejectedValueOnce(new Error('Backend unavailable')).mockResolvedValueOnce(response([]));
  render(<History />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Backend unavailable');
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
  expect(await screen.findByText('Your story starts here')).toBeInTheDocument();
});

test('does not announce removal when the API rejects it', async () => {
  fetch.mockResolvedValueOnce(response([entry()])).mockResolvedValueOnce(response({}, 500));
  render(<History />);
  await screen.findByRole('heading', { name: 'Video 26' });
  fireEvent.click(screen.getByRole('button', { name: 'Remove video 26 from history' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('500');
  expect(screen.queryByText('Video 26 removed from history.')).not.toBeInTheDocument();
});

test('pages through history and returns to the previous page if the next is empty', async () => {
  const fullPage = Array.from({ length: 20 }, (_, i) => entry(i + 1));
  fetch.mockResolvedValueOnce(response(fullPage)).mockResolvedValueOnce(response([])).mockResolvedValueOnce(response(fullPage));
  render(<History />);
  await screen.findByRole('heading', { name: 'Video 26' });
  expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: 'Next' }));
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(3));
  expect(fetch.mock.calls[1][0]).toContain('page=1');
  expect(await screen.findByRole('heading', { name: 'Video 26' })).toBeInTheDocument();
  expect(screen.getByText('Page 1')).toBeInTheDocument();
});

test('aborts the history load on unmount', async () => {
  fetch.mockImplementation(() => new Promise(() => {}));
  const { unmount } = render(<History />);
  const signal = fetch.mock.calls[0][1].signal;
  unmount();
  expect(signal.aborted).toBe(true);
  await act(async () => {});
});
