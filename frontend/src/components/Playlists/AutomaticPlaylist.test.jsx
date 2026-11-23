import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import AutomaticPlaylist from './AutomaticPlaylist';

const video = { videoId: 25, title: 'Recommended Java', channelId: 2, channelName: 'Test channel' };
const mix = id => ({ playlist: { id, title: 'For you', videoCount: 1, version: id }, videos: [{ videoId: 25, position: 0, video }], description: 'Based on your recent watches and likes.' });
const response = (body, status = 200) => ({ ok: status < 400, status, json: async () => body });
beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
afterEach(() => vi.unstubAllGlobals());

test('automatically loads a personal mix and starts playback without creating a manual playlist', async () => {
  fetch.mockResolvedValue(response(mix('snapshot1')));
  const onSelectVideo = vi.fn();
  render(<AutomaticPlaylist onSelectVideo={onSelectVideo} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Play your mix' }));
  expect(onSelectVideo).toHaveBeenCalledWith(25, video, 'automatic:snapshot1');
  expect(fetch.mock.calls[0]).toEqual(['/api/playlists/automatic', expect.objectContaining({ credentials: 'include', method: 'GET' })]);
  expect(fetch.mock.calls.some(([, options]) => options.method === 'POST')).toBe(false);
});

test('refresh generates a new snapshot and view opens its read-only playlist', async () => {
  fetch.mockResolvedValueOnce(response(mix('snapshot1'))).mockResolvedValueOnce(response(mix('snapshot2')));
  const onOpenPlaylist = vi.fn();
  render(<AutomaticPlaylist onOpenPlaylist={onOpenPlaylist} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Refresh recommendations' }));
  fireEvent.click(await screen.findByRole('button', { name: 'View your mix' }));
  expect(onOpenPlaylist).toHaveBeenCalledWith('automatic:snapshot2');
  expect(fetch.mock.calls[1][0]).toBe('/api/playlists/automatic/refresh');
  expect(fetch.mock.calls[1][1].method).toBe('POST');
});

test('empty catalog and cold start do not show fake recommendations', async () => {
  fetch.mockResolvedValue(response({ ...mix('snapshot1'), videos: [], description: 'Recent public videos while we learn what you enjoy.' }));
  render(<AutomaticPlaylist />);
  expect(await screen.findByText(/No new videos are available/)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Play your mix' })).not.toBeInTheDocument();
});

test('catalog outages can be retried without displaying stale results', async () => {
  fetch.mockResolvedValueOnce(response({ detail: 'Catalog temporarily unavailable' }, 503)).mockResolvedValueOnce(response(mix('snapshot1')));
  render(<AutomaticPlaylist />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Catalog temporarily unavailable');
  expect(screen.queryByRole('button', { name: 'Play your mix' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Retry recommendations' }));
  expect(await screen.findByRole('button', { name: 'Play your mix' })).toBeEnabled();
});

test('account changes abort and ignore the previous users mix', async () => {
  let finish;
  fetch.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; })).mockResolvedValueOnce(response(mix('new-user')));
  const onOpenPlaylist = vi.fn();
  const { rerender } = render(<AutomaticPlaylist key="old" onOpenPlaylist={onOpenPlaylist} />);
  const signal = fetch.mock.calls[0][1].signal;
  rerender(<AutomaticPlaylist key="new" onOpenPlaylist={onOpenPlaylist} />);
  await screen.findByRole('button', { name: 'View your mix' });
  await act(async () => finish(response(mix('old-user'))));
  expect(signal.aborted).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: 'View your mix' }));
  expect(onOpenPlaylist).toHaveBeenCalledWith('automatic:new-user');
});
