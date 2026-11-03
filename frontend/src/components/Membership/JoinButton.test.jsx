import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, vi } from 'vitest';
import JoinButton from './JoinButton.jsx';

const tiers = [
  { tierId: 1, channelId: 5, name: 'Supporter', description: null, level: 1, priceMinor: 299, currency: 'gbp',
    perks: [{ type: 'MEMBERS_ONLY_VIDEOS', description: 'Members-only videos', fromLevel: 1 }] },
  { tierId: 2, channelId: 5, name: 'VIP', description: null, level: 3, priceMinor: 999, currency: 'gbp',
    perks: [{ type: 'MEMBERS_ONLY_VIDEOS', description: 'Members-only videos', fromLevel: 1 },
      { type: 'EARLY_ACCESS', description: 'Early access', fromLevel: 3 }] },
];

function mockTiers(body) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve(body) }));
}

afterEach(() => vi.unstubAllGlobals());

test('shows nothing when the channel has no paid tiers', async () => {
  mockTiers({ channelId: 5, channelName: 'Demo', owner: false, tiers: [], currentMembership: null });
  const { container } = render(<JoinButton channelId={5} />);
  await vi.waitFor(() => expect(fetch).toHaveBeenCalled());
  expect(container).toBeEmptyDOMElement();
});

test('opens the tier modal with server-side prices and inherited perks', async () => {
  mockTiers({ channelId: 5, channelName: 'Demo', owner: false, tiers, currentMembership: null });
  render(<JoinButton channelId={5} />);

  await userEvent.click(await screen.findByRole('button', { name: 'Join' }));

  expect(screen.getByRole('dialog', { name: 'Join Demo' })).toBeInTheDocument();
  expect(screen.getByText('VIP')).toBeInTheDocument();
  expect(screen.getByText(/9\.99/)).toBeInTheDocument();
  expect(screen.getByText('Early access')).toBeInTheDocument();
});

test('a member at the required tier sees their membership instead of Join', async () => {
  mockTiers({ channelId: 5, channelName: 'Demo', owner: false, tiers,
    currentMembership: { membershipId: 1, tierName: 'Supporter', tierLevel: 1, hasAccess: true } });
  render(<JoinButton channelId={5} />);
  expect(await screen.findByRole('button', { name: 'Member · Supporter' })).toBeInTheDocument();
});

test('a member below the required tier is offered an upgrade, not a second membership', async () => {
  mockTiers({ channelId: 5, channelName: 'Demo', owner: false, tiers,
    currentMembership: { membershipId: 1, tierName: 'Supporter', tierLevel: 1, hasAccess: true } });
  render(<JoinButton channelId={5} requiredLevel={3} />);
  expect(await screen.findByRole('button', { name: 'Upgrade membership' })).toBeInTheDocument();
});

test('channel owners never see Join on their own channel', async () => {
  mockTiers({ channelId: 5, channelName: 'Demo', owner: true, tiers, currentMembership: null });
  const { container } = render(<JoinButton channelId={5} />);
  await vi.waitFor(() => expect(fetch).toHaveBeenCalled());
  expect(container).toBeEmptyDOMElement();
});
