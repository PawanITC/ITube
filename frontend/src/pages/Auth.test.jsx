import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, vi } from 'vitest';
import App from '../App';
import Auth from './Auth';

const user = { userId: 3, firstname: 'Ada', lastname: 'Lovelace', email: 'ada@example.com', channelId: 9, channelName: "Ada's Lab" };

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn());
});
afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

function type(label, value) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

test('register validates the form before calling the server', async () => {
  render(<Auth mode="register" onSignedIn={vi.fn()} onSwitchMode={vi.fn()} onHome={vi.fn()} />);
  type('Email', 'not-an-email');
  type('Password', 'short');
  type('Confirm password', 'different');
  fireEvent.click(screen.getByRole('button', { name: 'Create account' }));
  expect(await screen.findByText('Enter your first name.')).toBeInTheDocument();
  expect(screen.getByText('Enter a valid email address.')).toBeInTheDocument();
  expect(screen.getByText('Use at least 8 characters.')).toBeInTheDocument();
  expect(screen.getByText('Passwords don’t match.')).toBeInTheDocument();
  expect(fetch).not.toHaveBeenCalled();
});

test('register sends the details and signs the user in', async () => {
  fetch.mockResolvedValue({ ok: true, status: 201, json: async () => user });
  const onSignedIn = vi.fn();
  render(<Auth mode="register" onSignedIn={onSignedIn} onSwitchMode={vi.fn()} onHome={vi.fn()} />);
  type('First name', 'Ada');
  type('Last name', 'Lovelace');
  type('Email', ' ada@example.com ');
  type('Password', 'correct horse');
  type('Confirm password', 'correct horse');
  fireEvent.click(screen.getByRole('button', { name: 'Create account' }));
  await waitFor(() => expect(onSignedIn).toHaveBeenCalledWith(user));
  const [url, options] = fetch.mock.calls[0];
  expect(url).toBe('/api/auth/register');
  expect(JSON.parse(options.body)).toEqual({ firstname: 'Ada', lastname: 'Lovelace', email: 'ada@example.com', password: 'correct horse', channelName: null });
});

test('login shows a clear message for wrong credentials', async () => {
  fetch.mockResolvedValue({ ok: false, status: 401, json: async () => ({}) });
  const onSignedIn = vi.fn();
  render(<Auth mode="login" onSignedIn={onSignedIn} onSwitchMode={vi.fn()} onHome={vi.fn()} />);
  type('Email', 'ada@example.com');
  type('Password', 'wrong password');
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Incorrect email or password.');
  expect(onSignedIn).not.toHaveBeenCalled();
});

test('signing in from the header and signing out again', async () => {
  let signedIn = false;
  fetch.mockImplementation(async (url, options) => {
    if (url === '/api/auth/me') return signedIn ? { ok: true, status: 200, json: async () => user } : { ok: false, status: 401, json: async () => ({}) };
    if (url === '/api/auth/login') { signedIn = true; return { ok: true, status: 200, json: async () => user }; }
    if (url === '/api/auth/logout' && options?.method === 'POST') { signedIn = false; return { ok: true, status: 204, json: async () => null }; }
    return { ok: true, status: 200, json: async () => [] };
  });
  render(<App />);

  fireEvent.click(await screen.findByRole('button', { name: 'Sign in' }));
  expect(window.location.pathname).toBe('/login');
  type('Email', 'ada@example.com');
  type('Password', 'correct horse');
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

  const avatar = await screen.findByRole('button', { name: 'Account menu for Ada Lovelace' });
  expect(window.location.pathname).toBe('/');
  fireEvent.click(avatar);
  fireEvent.click(await screen.findByRole('menuitem', { name: 'Sign out' }));
  expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  expect(fetch.mock.calls.some(([url, options]) => url === '/api/auth/logout' && options?.method === 'POST')).toBe(true);
});
