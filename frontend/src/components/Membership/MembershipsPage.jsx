import { useEffect, useState } from 'react';
import { apiFetch, formatDate, formatPrice } from './api.js';

const STATUS_LABELS = {
  active: 'Active', trialing: 'Trial', past_due: 'Payment failed, retrying', unpaid: 'Unpaid',
  canceled: 'Ended', incomplete: 'Awaiting payment', incomplete_expired: 'Payment not completed', paused: 'Paused',
};

function MembershipRow({ membership, onChanged, onOpenChannel }) {
  const [tiers, setTiers] = useState([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const live = ['active', 'trialing', 'past_due', 'unpaid', 'paused', 'incomplete'].includes(membership.status);
  const changeable = ['active', 'trialing'].includes(membership.status);

  useEffect(() => {
    if (!changeable) return;
    apiFetch(`/api/memberships/channels/${membership.channelId}/tiers`).then(result => setTiers(result.tiers)).catch(() => setTiers([]));
  }, [membership.channelId, changeable]);

  async function run(action) {
    setBusy(true);
    setError('');
    try {
      onChanged(await action());
    } catch (actionError) {
      setError(actionError.message);
    } finally {
      setBusy(false);
    }
  }

  const changeTier = tierId => run(() => apiFetch(`/api/memberships/${membership.membershipId}/tier`, { method: 'POST', body: JSON.stringify({ tierId: Number(tierId) }) }));
  const cancel = () => {
    if (window.confirm(`Cancel your ${membership.channelName} membership? You keep access until ${formatDate(membership.currentPeriodEnd)}.`)) {
      run(() => apiFetch(`/api/memberships/${membership.membershipId}/cancel`, { method: 'POST' }));
    }
  };
  const resume = () => run(() => apiFetch(`/api/memberships/${membership.membershipId}/resume`, { method: 'POST' }));

  return (
    <li className="membership-row">
      <span className="channel-page-avatar small">{membership.channelName.trim().slice(0, 1).toUpperCase() || '?'}</span>
      <div className="membership-info">
        <a href={`/channel/${membership.channelId}`} onClick={event => { event.preventDefault(); onOpenChannel(membership.channelId); }}><strong>{membership.channelName}</strong></a>
        <p>{membership.tierName} · {STATUS_LABELS[membership.status] || membership.status}
          {live && membership.currentPeriodEnd && (membership.cancelAtPeriodEnd
            ? ` · Ends ${formatDate(membership.currentPeriodEnd)}`
            : ` · Renews ${formatDate(membership.currentPeriodEnd)}`)}
        </p>
        {membership.status === 'past_due' && <p className="membership-warning">Your last payment failed. Update your payment method under Manage billing to keep access.</p>}
        {error && <p className="subscription-error" role="alert">{error}</p>}
      </div>
      <div className="membership-actions">
        {changeable && tiers.length > 1 && !membership.cancelAtPeriodEnd && (
          <select aria-label="Change tier" value={membership.tierId} disabled={busy} onChange={event => changeTier(event.target.value)}>
            {tiers.map(tier => <option key={tier.tierId} value={tier.tierId}>{tier.name} · {formatPrice(tier.priceMinor, tier.currency)}/mo</option>)}
          </select>
        )}
        {live && !membership.cancelAtPeriodEnd && <button type="button" className="text-button" disabled={busy} onClick={cancel}>Cancel</button>}
        {live && membership.cancelAtPeriodEnd && <button type="button" className="join-button" disabled={busy} onClick={resume}>Keep membership</button>}
      </div>
    </li>
  );
}

export default function MembershipsPage({ onHome, onOpenChannel }) {
  const [memberships, setMemberships] = useState(null);
  const [error, setError] = useState('');
  const [portalError, setPortalError] = useState('');

  useEffect(() => {
    apiFetch('/api/memberships/me').then(setMemberships).catch(loadError => setError(loadError.message));
  }, []);

  async function openPortal() {
    setPortalError('');
    try {
      const { url } = await apiFetch('/api/payments/billing-portal', { method: 'POST' });
      window.location.assign(url);
    } catch (portalOpenError) {
      setPortalError(portalOpenError.message);
    }
  }

  function replace(updated) {
    setMemberships(current => current.map(item => item.membershipId === updated.membershipId ? updated : item));
  }

  return (
    <main className="memberships-page">
      <button type="button" className="watch-back" onClick={onHome}>‹ Back to videos</button>
      <header className="memberships-header">
        <div><h1>Memberships</h1><p>Paid channel memberships. Free subscriptions are listed in the sidebar.</p></div>
        {memberships?.length > 0 && <button type="button" className="subscribe-button" onClick={openPortal}>Manage billing</button>}
      </header>
      {portalError && <p className="subscription-error" role="alert">{portalError}</p>}
      {error ? <p className="channel-page-message" role="alert">{error}</p>
        : memberships === null ? <p className="channel-page-message">Loading…</p>
          : memberships.length === 0 ? <p className="channel-page-message">You haven't joined any channels yet. Look for the Join button on a channel you love.</p>
            : <ul className="membership-list">{memberships.map(membership => <MembershipRow key={membership.membershipId} membership={membership} onChanged={replace} onOpenChannel={onOpenChannel} />)}</ul>}
    </main>
  );
}
