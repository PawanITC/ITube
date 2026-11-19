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
  // Only the sign-in options were asked for, nothing was submitted
  expect(fetch.mock.calls.every(([url]) => url === '/api/auth/providers')).toBe(true);
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
  const [, options] = fetch.mock.calls.find(([url]) => url === '/api/auth/register');
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

test('offers Cognito sign-in when the server has it configured', async () => {
  fetch.mockImplementation(async url => (url === '/api/auth/providers'
    ? { ok: true, status: 200, json: async () => ({ password: true, cognito: true }) }
    : { ok: false, status: 404, json: async () => ({}) }));
  const assign = vi.fn();
  vi.stubGlobal('location', { ...window.location, assign, search: '' });
  render(<Auth mode="login" onSignedIn={vi.fn()} onSwitchMode={vi.fn()} onHome={vi.fn()} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Continue with Cognito' }));
  expect(assign).toHaveBeenCalledWith('/api/auth/oauth/cognito/authorize');
});

test('hides Cognito sign-in when it is not configured', async () => {
  fetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({ password: true, cognito: false }) });
  render(<Auth mode="login" onSignedIn={vi.fn()} onSwitchMode={vi.fn()} onHome={vi.fn()} />);
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/auth/providers', expect.anything()));
  expect(screen.queryByRole('button', { name: 'Continue with Cognito' })).not.toBeInTheDocument();
});

test('explains a failed Cognito sign-in', async () => {
  fetch.mockResolvedValue({ ok: false, status: 404, json: async () => ({}) });
  window.history.replaceState(null, '', '/login?error=cognito-unverified');
  render(<Auth mode="login" onSignedIn={vi.fn()} onSwitchMode={vi.fn()} onHome={vi.fn()} />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Verify your email address');
});
