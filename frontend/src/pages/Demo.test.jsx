import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, vi } from 'vitest';
import Demo from './Demo.jsx';

const json = body => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) });

function mockApi({ mine, tiers = [], videos = [] }) {
  const fetchMock = vi.fn((url, options = {}) => {
    if (url === '/api/channels/mine') return json(mine);
    if (url.startsWith('/api/channels/search')) return json([{ channelId: 1, channelName: 'Demo Channel' }]);
    if (url === '/api/memberships/channels/1/tiers' && options.method === 'POST') return json({});
    if (url === '/api/memberships/channels/1/tiers') return json({ tiers });
    if (url === '/api/channels/1') return json({ videos });
    return Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve({}) });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => vi.unstubAllGlobals());

test('the creator can create the missing example tiers in one click', async () => {
  const existing = [{ tierId: 7, level: 1, name: 'Supporter', priceMinor: 299, currency: 'gbp' }];
  const fetchMock = mockApi({ mine: [{ channelId: 1, channelName: 'Demo Channel' }], tiers: existing });
  render(<Demo onHome={() => {}} />);

  expect(await screen.findByText('Demo creator (owns "Demo Channel")')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: /Create example tiers/ }));

  await waitFor(() => {
    const created = fetchMock.mock.calls.filter(([url, options]) => url === '/api/memberships/channels/1/tiers' && options?.method === 'POST');
    // Level 1 already exists, so only Member (2) and VIP (3) are created
    expect(created.map(([, options]) => JSON.parse(options.body).level)).toEqual([2, 3]);
  });
});

test('a viewer sees the steps but cannot create tiers or change visibility', async () => {
  mockApi({ mine: [{ channelId: 2, channelName: 'Demo Viewer' }],
    videos: [{ videoId: 5, title: 'Members special', visibility: 'MEMBERS_ONLY', requiredTierLevel: null }] });
  render(<Demo onHome={() => {}} />);

  expect(await screen.findByText('Demo viewer')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /Create example tiers/ })).toBeDisabled();
  expect(await screen.findByText('Members special')).toBeInTheDocument();
  expect(screen.queryByRole('combobox', { name: /Visibility of/ })).not.toBeInTheDocument();
  expect(screen.getByText('4242 4242 4242 4242')).toBeInTheDocument();
});
