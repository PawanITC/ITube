import { act, render, screen } from '@testing-library/react';
import { vi } from 'vitest';
import App from './App';

test('renders the video feed', () => {
  render(<App />);
  expect(screen.getByRole('main')).toBeInTheDocument();
});

test('opens History through hash navigation and returns home', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [] }));
  const { unmount } = render(<App />);
  try {
    await act(async () => {
      window.history.replaceState(null, '', '#history');
      window.dispatchEvent(new Event('hashchange'));
    });
    expect(await screen.findByRole('heading', { name: 'Watch history' })).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'History' }).every(link => link.getAttribute('aria-current') === 'page')).toBe(true);
    await act(async () => {
      window.history.replaceState(null, '', '#home');
      window.dispatchEvent(new Event('hashchange'));
    });
    expect(screen.queryByRole('heading', { name: 'Watch history' })).not.toBeInTheDocument();
  } finally {
    unmount();
    window.history.replaceState(null, '', '/');
    vi.unstubAllGlobals();
  }
});
