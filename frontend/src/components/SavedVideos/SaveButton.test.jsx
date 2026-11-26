import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import SaveButton from './SaveButton';

const response = (body, status = 200) => ({ ok: status < 400, status, json: async () => body });
beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
afterEach(() => vi.unstubAllGlobals());

test('loads status and saves and removes using the signed-in session', async () => {
  fetch.mockResolvedValueOnce(response({ saved: false })).mockResolvedValueOnce(response(null, 204)).mockResolvedValueOnce(response(null, 204));
  render(<SaveButton videoId={25} />);
  const save = screen.getByRole('button', { name: 'Save video to Watch later' });
  await waitFor(() => expect(save).toBeEnabled());
  fireEvent.click(save);
  const remove = await screen.findByRole('button', { name: 'Remove video from Watch later' });
  expect(remove).toHaveAttribute('aria-pressed', 'true');
  expect(fetch.mock.calls[1]).toEqual(['/api/saved-videos/25', expect.objectContaining({ method: 'PUT', credentials: 'include' })]);
  fireEvent.click(remove);
  await waitFor(() => expect(screen.getByRole('button', { name: 'Save video to Watch later' })).toBeEnabled());
  expect(fetch.mock.calls[2][1].method).toBe('DELETE');
});

test('a failed save retains the old state and retry reconciles the server state', async () => {
  fetch.mockResolvedValueOnce(response({ saved: false })).mockResolvedValueOnce(response({}, 503)).mockResolvedValueOnce(response({ saved: true }));
  render(<SaveButton videoId={25} />);
  const button = screen.getByRole('button', { name: 'Save video to Watch later' });
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
  expect(await screen.findByRole('alert')).toHaveTextContent('503');
  expect(button).toHaveAttribute('aria-pressed', 'false');
  expect(screen.queryByText('Saved to Watch later.')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
  expect(await screen.findByRole('button', { name: 'Remove video from Watch later' })).toBeEnabled();
});

test('anonymous viewers get a sign-in prompt', async () => {
  fetch.mockResolvedValueOnce(response({}, 401));
  render(<SaveButton videoId={25} />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Sign in');
  expect(screen.getByRole('button', { name: 'Save video to Watch later' })).toBeDisabled();
});

test('double clicks send only one save request', async () => {
  let finish;
  fetch.mockResolvedValueOnce(response({ saved: false })).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  render(<SaveButton videoId={25} />);
  const button = screen.getByRole('button', { name: 'Save video to Watch later' });
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
  fireEvent.click(button);
  expect(fetch).toHaveBeenCalledTimes(2);
  await act(async () => finish(response(null, 204)));
  expect(screen.getByRole('button', { name: 'Remove video from Watch later' })).toBeEnabled();
});

test('switching video or account aborts and ignores stale status responses', async () => {
  let finish;
  fetch.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; })).mockResolvedValueOnce(response({ saved: true }));
  const { rerender } = render(<SaveButton key="25:1" videoId={25} />);
  const signal = fetch.mock.calls[0][1].signal;
  rerender(<SaveButton key="25:2" videoId={25} />);
  await screen.findByRole('button', { name: 'Remove video from Watch later' });
  await act(async () => finish(response({ saved: false })));
  expect(signal.aborted).toBe(true);
  expect(screen.getByRole('button', { name: 'Remove video from Watch later' })).toHaveAttribute('aria-pressed', 'true');
});

test('signed-out viewers are asked to sign in when they click', () => {
  const onSignIn = vi.fn();
  render(<SaveButton videoId={25} signedIn={false} onSignIn={onSignIn} />);
  const button = screen.getByRole('button', { name: 'Save video to Watch later' });
  expect(button).toBeEnabled();
  expect(screen.queryByRole('alert')).toBeNull();
  fireEvent.click(button);
  expect(screen.getByRole('alert')).toHaveTextContent('Sign in to use this feature');
  fireEvent.click(screen.getByRole('button', { name: 'Sign In' }));
  expect(onSignIn).toHaveBeenCalledTimes(1);
  expect(fetch).not.toHaveBeenCalled();
});
