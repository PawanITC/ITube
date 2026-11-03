import { useEffect, useState } from 'react';
import { apiFetch, formatPrice } from '../components/Membership/api.js';
import './Demo.css';

// Development-only showcase. App.jsx only loads this page when Vite runs in dev mode,
// so it is left out of production builds entirely.

const EXAMPLE_TIERS = [
  { name: 'Supporter', level: 1, priceMinor: 299, perks: [['MEMBERS_ONLY_VIDEOS', 'Members-only videos'], ['LOYALTY_BADGE', 'Loyalty badge']] },
  { name: 'Member', level: 2, priceMinor: 499, perks: [['EARLY_ACCESS', 'Early access to videos']] },
  { name: 'VIP', level: 3, priceMinor: 999, perks: [['MEMBERS_ONLY_CHAT', 'Members-only live chat'], ['CUSTOM_EMOJI', 'Custom emoji']] },
];

const VISIBILITY_OPTIONS = [
  ['PUBLIC|', 'Public'],
  ['MEMBERS_ONLY|', 'Members only (any tier)'],
  ['MEMBERS_ONLY|3', 'Members only (VIP, level 3+)'],
  ['UNLISTED|', 'Unlisted'],
  ['PRIVATE|', 'Private'],
];

const LINKS = [
  ['Swagger (every API endpoint)', 'http://localhost:8080/swagger-ui.html'],
  ['Grafana (logs, traces, metrics)', 'http://localhost:3080'],
  ['Stripe: products (one per tier)', 'https://dashboard.stripe.com/test/products'],
  ['Stripe: subscriptions', 'https://dashboard.stripe.com/test/subscriptions'],
  ['Stripe: payments', 'https://dashboard.stripe.com/test/payments'],
  ['Stripe: webhook events', 'https://dashboard.stripe.com/test/events'],
];

function Section({ number, title, children }) {
  return (
    <section className="demo-card" aria-labelledby={`demo-${number}`}>
      <h2 id={`demo-${number}`}><span className="demo-step">{number}</span>{title}</h2>
      {children}
    </section>
  );
}

export default function Demo({ onHome }) {
  const [identity, setIdentity] = useState({ loading: true });
  const [demoChannel, setDemoChannel] = useState(null);
  const [tiers, setTiers] = useState([]);
  const [videos, setVideos] = useState([]);
  const [busy, setBusy] = useState('');
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');

  async function load() {
    let mine = [];
    try { mine = await apiFetch('/api/channels/mine'); } catch { /* not signed in */ }
    const role = mine.some(c => c.channelName === 'Demo Channel') ? 'creator'
      : mine.some(c => c.channelName === 'Demo Viewer') ? 'viewer' : mine.length ? 'other' : 'none';
    setIdentity({ loading: false, role, channelName: mine[0]?.channelName });

    // Find the demo creator's channel whoever is signed in (search hides your own channels)
    let channel = mine.find(c => c.channelName === 'Demo Channel');
    if (!channel) {
      const found = await apiFetch('/api/channels/search?q=Demo%20Channel').catch(() => []);
      channel = found.find(c => c.channelName === 'Demo Channel');
    }
    setDemoChannel(channel || null);
    if (!channel) return;
    const [tierInfo, details] = await Promise.all([
      apiFetch(`/api/memberships/channels/${channel.channelId}/tiers`).catch(() => null),
      apiFetch(`/api/channels/${channel.channelId}`).catch(() => null),
    ]);
    setTiers(tierInfo?.tiers || []);
    setVideos(details?.videos || []);
  }

  useEffect(() => { load(); }, []);

  async function run(label, action, successMessage) {
    setBusy(label);
    setError('');
    setNotice('');
    try {
      await action();
      if (successMessage) setNotice(successMessage);
      await load();
    } catch (actionError) {
      setError(actionError.message);
    } finally {
      setBusy('');
    }
  }

  // Switching user reloads the page so the header, sidebar and everything else pick up the new session
  async function switchTo(endpoint) {
    setBusy(endpoint);
    try {
      await apiFetch(endpoint, { method: 'POST' });
      window.location.reload();
    } catch (switchError) {
      setError(switchError.message);
      setBusy('');
    }
  }

  const createExampleTiers = () => run('tiers', async () => {
    const existing = new Set(tiers.map(tier => tier.level));
    for (const tier of EXAMPLE_TIERS.filter(t => !existing.has(t.level))) {
      await apiFetch(`/api/memberships/channels/${demoChannel.channelId}/tiers`, {
        method: 'POST',
        body: JSON.stringify({ name: tier.name, level: tier.level, priceMinor: tier.priceMinor,
          perks: tier.perks.map(([type, description]) => ({ type, description })) }),
      });
    }
  }, 'Example tiers are ready. Each one is now a product in Stripe.');

  const changeVisibility = (videoId, value) => run(`video-${videoId}`, () => {
    const [visibility, level] = value.split('|');
    return apiFetch(`/api/videos/${videoId}/visibility`, {
      method: 'PATCH',
      body: JSON.stringify({ visibility, requiredTierLevel: level ? Number(level) : null }),
    });
  }, 'Visibility updated.');

  const isCreator = identity.role === 'creator';
  const roleLabel = { creator: 'Demo creator (owns "Demo Channel")', viewer: 'Demo viewer',
    other: `Another user (${identity.channelName})`, none: 'Not signed in' }[identity.role];

  return (
    <main className="demo-page">
      <button type="button" className="watch-back" onClick={onHome}>‹ Back to videos</button>
      <header className="demo-heading">
        <p className="history-eyebrow">DEVELOPMENT ONLY</p>
        <h1>Feature showcase</h1>
        <p>A guided tour of the paid memberships features, with shortcuts so you can demo them in one browser window. This page only exists when running locally.</p>
      </header>

      <div className="demo-status" role="status" aria-live="polite">
        {notice && <p className="history-notice">{notice}</p>}
        {error && <p className="history-error">{error}</p>}
      </div>

      <Section number="1" title="Switch between creator and viewer">
        <p>Most features need two people: a <strong>creator</strong> who sells memberships and a <strong>viewer</strong> who buys one. Switch here instead of using a private window.</p>
        <p className="demo-identity">You are: <strong>{identity.loading ? '…' : roleLabel}</strong></p>
        <div className="history-actions">
          <button type="button" className="history-button" disabled={!!busy || isCreator} onClick={() => switchTo('/api/session/dev')}>Be the creator</button>
          <button type="button" className="history-button" disabled={!!busy || identity.role === 'viewer'} onClick={() => switchTo('/api/session/dev/viewer')}>Be the viewer</button>
          <button type="button" className="history-button" disabled={!!busy} onClick={() => switchTo('/api/session/dev/random')}>New random user</button>
        </div>
      </Section>

      <Section number="2" title="Creators set up paid membership tiers">
        <p>A creator offers monthly tiers. Higher tiers include the perks of lower ones, and each tier becomes a product with a monthly price in Stripe. Creators manage them on their own channel page.</p>
        {tiers.length > 0 ? (
          <ul className="demo-list">{tiers.map(tier => <li key={tier.tierId}><strong>Level {tier.level} · {tier.name}</strong> · {formatPrice(tier.priceMinor, tier.currency)}/month</li>)}</ul>
        ) : <p className="demo-muted">The demo channel has no tiers yet.</p>}
        <div className="history-actions">
          <button type="button" className="history-button" disabled={!!busy || !isCreator || !demoChannel || tiers.length >= EXAMPLE_TIERS.length} onClick={createExampleTiers}>
            {busy === 'tiers' ? 'Creating…' : 'Create example tiers (£2.99 / £4.99 / £9.99)'}
          </button>
          {demoChannel && <a className="history-button" href={`/channel/${demoChannel.channelId}`}>Open the creator's channel page</a>}
        </div>
        {!isCreator && <p className="demo-muted">Switch to the creator to create tiers.</p>}
      </Section>

      <Section number="3" title="Creators make videos members-only">
        <p>Any video can be public, unlisted, private or <strong>members only</strong>, optionally for a minimum tier. The server checks membership before sending a single byte of video, so hiding the player isn't the only protection.</p>
        {videos.length > 0 ? (
          <ul className="demo-list">
            {videos.map(video => (
              <li key={video.videoId} className="demo-video">
                <a href={`/watch/${video.videoId}`}>{video.title}</a>
                {isCreator ? (
                  <select aria-label={`Visibility of ${video.title}`} disabled={!!busy}
                    value={`${video.visibility}|${video.visibility === 'MEMBERS_ONLY' && video.requiredTierLevel ? video.requiredTierLevel : ''}`}
                    onChange={event => changeVisibility(video.videoId, event.target.value)}>
                    {VISIBILITY_OPTIONS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                  </select>
                ) : <span className="demo-muted">{video.visibility === 'MEMBERS_ONLY' ? 'Members only' : video.visibility.toLowerCase()}</span>}
              </li>
            ))}
          </ul>
        ) : <p className="demo-muted">No videos on the demo channel yet. As the creator, upload any short .mp4 file.</p>}
        <div className="history-actions">
          <a className={`history-button${isCreator ? '' : ' is-disabled'}`} href={isCreator ? '/upload' : undefined} aria-disabled={!isCreator}>Upload a video</a>
        </div>
      </Section>

      <Section number="4" title="Viewers join with Stripe Checkout">
        <p>As the viewer, open a members-only video or the creator's channel and click <strong>Join</strong>. Payment happens on Stripe's own page, so iTube never sees card details. Access is granted only when Stripe confirms the payment, never by the return page.</p>
        <div className="demo-card-number">
          <span>Test card</span><code>4242 4242 4242 4242</code><span>any future date · any CVC · any postcode</span>
          <button type="button" className="history-button" onClick={() => navigator.clipboard?.writeText('4242424242424242').then(() => setNotice('Card number copied.'))}>Copy</button>
        </div>
        <p className="demo-muted">Other cards: <code>4000 0000 0000 0002</code> is declined, <code>4000 0025 0000 3155</code> asks for 3D Secure.</p>
        {demoChannel && <a className="history-button" href={`/channel/${demoChannel.channelId}`}>Go to the creator's channel</a>}
      </Section>

      <Section number="5" title="Viewers manage their memberships">
        <p>Upgrade or downgrade (prorated), cancel at the end of the paid period, or open Stripe's billing page to change their card.</p>
        <a className="history-button" href="/memberships">Open My memberships</a>
      </Section>

      <Section number="6" title="Free Subscribe is separate from paid Join">
        <p><strong>Subscribe</strong> is free and never involves payment. <strong>Join</strong> is the paid membership. They're separate buttons on the channel page.</p>
      </Section>

      <Section number="7" title="Watch history">
        <p>Videos you've watched are listed on the History page.</p>
        <a className="history-button" href="/history">Open History</a>
      </Section>

      <Section number="8" title="Behind the scenes">
        <p>Useful when explaining how it works. Stripe links open the test-mode Dashboard.</p>
        <ul className="demo-links">{LINKS.map(([label, href]) => <li key={href}><a href={href} target="_blank" rel="noreferrer">{label} ↗</a></li>)}</ul>
      </Section>
    </main>
  );
}
