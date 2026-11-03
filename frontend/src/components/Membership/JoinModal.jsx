import { useEffect, useRef, useState } from 'react';
import { formatPrice, startMembershipCheckout } from './api.js';

export default function JoinModal({ channelName, tiers, highlightLevel, onClose }) {
  const [joiningTierId, setJoiningTierId] = useState(null);
  const [error, setError] = useState('');
  const dialogRef = useRef(null);

  useEffect(() => {
    dialogRef.current?.focus();
    const closeOnEscape = event => { if (event.key === 'Escape') onClose(); };
    document.addEventListener('keydown', closeOnEscape);
    return () => document.removeEventListener('keydown', closeOnEscape);
  }, [onClose]);

  async function join(tierId) {
    setJoiningTierId(tierId);
    setError('');
    try {
      await startMembershipCheckout(tierId);
    } catch (joinError) {
      setError(joinError.message);
      setJoiningTierId(null);
    }
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="join-title" tabIndex={-1} ref={dialogRef} onClick={event => event.stopPropagation()}>
        <button type="button" className="modal-close" aria-label="Close" onClick={onClose}>×</button>
        <h2 id="join-title">Join {channelName}</h2>
        <p className="modal-subtitle">Monthly membership. Cancel anytime. Payment is handled securely by Stripe.</p>
        <div className="tier-list">
          {tiers.map(tier => (
            <section className={`tier-card${highlightLevel && tier.level >= highlightLevel ? ' is-eligible' : ''}`} key={tier.tierId}>
              <header>
                <h3>{tier.name}</h3>
                <p className="tier-price">{formatPrice(tier.priceMinor, tier.currency)}<span>/month</span></p>
              </header>
              {tier.description && <p className="tier-description">{tier.description}</p>}
              <ul className="tier-perks">
                {tier.perks.map((perk, index) => (
                  <li key={index} className={perk.fromLevel < tier.level ? 'is-inherited' : ''}>{perk.description}</li>
                ))}
              </ul>
              <button type="button" className="join-confirm" disabled={joiningTierId !== null} onClick={() => join(tier.tierId)}>
                {joiningTierId === tier.tierId ? 'Opening checkout…' : 'Join'}
              </button>
            </section>
          ))}
        </div>
        {error && <p className="subscription-error" role="alert">{error}</p>}
      </div>
    </div>
  );
}
