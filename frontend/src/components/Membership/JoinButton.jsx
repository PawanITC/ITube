import { useEffect, useState } from 'react';
import { apiFetch } from './api.js';
import JoinModal from './JoinModal.jsx';

/**
 * Paid "Join" button. Hidden when the channel has no tiers or the viewer owns it.
 * This is separate from the free Subscribe button, which never involves payment.
 */
export default function JoinButton({ channelId, requiredLevel, onOpenMemberships, label = 'Join' }) {
  const [info, setInfo] = useState(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    apiFetch(`/api/memberships/channels/${channelId}/tiers`)
      .then(result => { if (!cancelled) setInfo(result); })
      .catch(() => { if (!cancelled) setInfo(null); });
    return () => { cancelled = true; };
  }, [channelId]);

  if (!info || info.owner || info.tiers.length === 0) return null;

  const membership = info.currentMembership;
  if (membership?.hasAccess && (!requiredLevel || membership.tierLevel >= requiredLevel)) {
    return <button type="button" className="join-button is-member" onClick={onOpenMemberships}>Member · {membership.tierName}</button>;
  }
  // A member below the required tier upgrades from their memberships page rather than joining twice
  if (membership) {
    return <button type="button" className="join-button" onClick={onOpenMemberships}>Upgrade membership</button>;
  }
  const tiers = requiredLevel ? info.tiers.filter(tier => tier.level >= requiredLevel) : info.tiers;
  return (
    <>
      <button type="button" className="join-button" onClick={() => setOpen(true)}>{label}</button>
      {open && <JoinModal channelName={info.channelName} tiers={tiers} highlightLevel={requiredLevel} onClose={() => setOpen(false)} />}
    </>
  );
}
