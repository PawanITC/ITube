import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import WatchPage from './WatchPage';

// Exercise the real player component and History client without changing playback code.
vi.mock('../Membership/JoinButton.jsx', () => ({ default: () => null }));

const video = id => ({ videoId: id, channelId: 2, title: `Video ${id}`, channelName: 'Test', processingStatus: 'READY', visibility: 'PUBLIC' });
const props = id => ({ videoId: id, videos: [video(25), video(26)], videosLoading: false, videosError: '', session: { userId: 7 }, onHome: vi.fn() });
const response = (body, status = 200) => ({ ok: status < 400, status, json: async () => body });
const historyPosts = () => fetch.mock.calls.filter(([url, options]) => url === '/api/history' && options?.method === 'POST');

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async (url, options) => {
    if (url === '/api/history') return response({ id: 1 }, 201);
    if (url.endsWith('/playback-authorisation')) {
      const id = Number(url.split('/')[3]);
      return response({ videoId: id, allowed: true, sourceUrl: `/api/videos/${id}/file` });
    }
    if (url === '/api/videos') return response([video(25), video(26)]);
    if (url === '/api/session/current') return response({ userId: 7 });
    return response([]);
  }));
});
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

test('records when playback starts, not when merely opening the watch page', async () => {
  const { container } = render(<WatchPage {...props(25)} />);
  await waitFor(() => expect(container.querySelector('video')).toBeInTheDocument());
  expect(historyPosts()).toHaveLength(0);
  fireEvent.play(container.querySelector('video'));
  expect(historyPosts()).toHaveLength(0);
  fireEvent.playing(container.querySelector('video'));
  await waitFor(() => expect(historyPosts()).toHaveLength(1));
  const [, options] = historyPosts()[0];
  expect(options.credentials).toBe('include');
  expect(JSON.parse(options.body)).toEqual({ videoId: 25 });
});

test('pause, resume, buffering and source restarts do not duplicate a successful record', async () => {
  const { container } = render(<WatchPage {...props(25)} />);
  await waitFor(() => expect(container.querySelector('video')).toBeInTheDocument());
  fireEvent.playing(container.querySelector('video'));
  await act(async () => {});
  fireEvent.pause(container.querySelector('video'));
  fireEvent.playing(container.querySelector('video'));
  fireEvent.waiting(container.querySelector('video'));
  fireEvent.playing(container.querySelector('video'));
  expect(historyPosts()).toHaveLength(1);
});

test('repeated playing events while the history request is pending send only one POST', async () => {
  const original = fetch.getMockImplementation();
  let complete;
  fetch.mockImplementation((url, options) => url === '/api/history'
    ? new Promise(resolve => { complete = resolve; }) : original(url, options));
  const { container } = render(<WatchPage {...props(25)} />);
  await waitFor(() => expect(container.querySelector('video')).toBeInTheDocument());
  fireEvent.playing(container.querySelector('video'));
  fireEvent.playing(container.querySelector('video'));
  expect(historyPosts()).toHaveLength(1);
  await act(async () => complete(response({ id: 1 }, 201)));
  fireEvent.playing(container.querySelector('video'));
  expect(historyPosts()).toHaveLength(1);
});

test('records the next video, and a later return to the first video', async () => {
  const { container, rerender } = render(<WatchPage {...props(25)} />);
  await waitFor(() => expect(container.querySelector('video')).toHaveAttribute('src', '/api/videos/25/file'));
  fireEvent.playing(container.querySelector('video'));
  rerender(<WatchPage {...props(26)} />);
  await waitFor(() => expect(container.querySelector('video')).toHaveAttribute('src', '/api/videos/26/file'));
  fireEvent.playing(container.querySelector('video'));
  rerender(<WatchPage {...props(25)} />);
  await waitFor(() => expect(container.querySelector('video')).toHaveAttribute('src', '/api/videos/25/file'));
  fireEvent.playing(container.querySelector('video'));
  expect(historyPosts().map(([, options]) => JSON.parse(options.body).videoId)).toEqual([25, 26, 25]);
});

test('a history failure does not stop the player and a later playing event can retry', async () => {
  const original = fetch.getMockImplementation();
  let failed = false;
  fetch.mockImplementation(async (url, options) => {
    if (url === '/api/history' && !failed) { failed = true; return response({}, 500); }
    return original(url, options);
  });
  const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
  const { container } = render(<WatchPage {...props(25)} />);
  await waitFor(() => expect(container.querySelector('video')).toBeInTheDocument());
  fireEvent.playing(container.querySelector('video'));
  await waitFor(() => expect(warn).toHaveBeenCalled());
  expect(container.querySelector('video')).toBeInTheDocument();
  fireEvent.playing(container.querySelector('video'));
  await waitFor(() => expect(historyPosts()).toHaveLength(2));
});

test('anonymous playback remains playable when the History API returns 401', async () => {
  const original = fetch.getMockImplementation();
  fetch.mockImplementation((url, options) => url === '/api/history' ? Promise.resolve(response({}, 401)) : original(url, options));
  const { container } = render(<WatchPage {...props(25)} session={null} />);
  await waitFor(() => expect(container.querySelector('video')).toBeInTheDocument());
  fireEvent.playing(container.querySelector('video'));
  await act(async () => {});
  expect(container.querySelector('video')).toBeInTheDocument();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});

test('locked playback creates no history record', async () => {
  const original = fetch.getMockImplementation();
  fetch.mockImplementation((url, options) => url.endsWith('/playback-authorisation')
    ? Promise.resolve(response({ videoId: 25, allowed: false, reason: 'SIGN_IN_REQUIRED' })) : original(url, options));
  let view;
  // Flush the mocked authorisation promise before asserting the denied-player branch.
  await act(async () => { view = render(<WatchPage {...props(25)} />); });
  const { container } = view;
  expect(screen.getByRole('region', { name: 'Members-only video' })).toBeInTheDocument();
  expect(container.querySelector('video')).not.toBeInTheDocument();
  expect(historyPosts()).toHaveLength(0);
});
