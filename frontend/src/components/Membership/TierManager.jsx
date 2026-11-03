import { useEffect, useState } from 'react';
import { apiFetch, formatPrice, PERK_LABELS } from './api.js';

const EMPTY_FORM = { name: '', description: '', level: 1, price: '', perks: ['MEMBERS_ONLY_VIDEOS'] };

/** Creator Studio: lets a channel owner create, re-price and archive membership tiers. */
export default function TierManager({ channelId }) {
  const [tiers, setTiers] = useState([]);
  const [form, setForm] = useState(EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  function load() {
    apiFetch(`/api/memberships/channels/${channelId}/tiers`).then(result => setTiers(result.tiers)).catch(loadError => setError(loadError.message));
  }
  useEffect(load, [channelId]);

  function togglePerk(type) {
    setForm(current => ({ ...current, perks: current.perks.includes(type) ? current.perks.filter(p => p !== type) : [...current.perks, type] }));
  }

  async function createTier(event) {
    event.preventDefault();
    setSaving(true);
    setError('');
    try {
      await apiFetch(`/api/memberships/channels/${channelId}/tiers`, {
        method: 'POST',
        body: JSON.stringify({
          name: form.name.trim(),
          description: form.description.trim(),
          level: Number(form.level),
          // Pounds in the form, pence on the wire. The server re-validates the range.
          priceMinor: Math.round(Number(form.price) * 100),
          perks: form.perks.map(type => ({ type, description: PERK_LABELS[type] })),
        }),
      });
      setForm(EMPTY_FORM);
      load();
    } catch (createError) {
      setError(createError.message);
    } finally {
      setSaving(false);
    }
  }

  async function archive(tier) {
    if (!window.confirm(`Stop offering "${tier.name}"? Existing members keep their membership until they cancel.`)) return;
    try {
      await apiFetch(`/api/memberships/tiers/${tier.tierId}`, { method: 'DELETE' });
      load();
    } catch (archiveError) {
      setError(archiveError.message);
    }
  }

  return (
    <section className="tier-manager">
      <h2>Channel memberships</h2>
      <p className="modal-subtitle">Offer monthly paid tiers. Higher levels automatically include the perks of lower levels.</p>
      {tiers.length > 0 && (
        <ul className="tier-manager-list">
          {tiers.map(tier => (
            <li key={tier.tierId}>
              <span><strong>Level {tier.level} · {tier.name}</strong> · {formatPrice(tier.priceMinor, tier.currency)}/month</span>
              <button type="button" className="text-button" onClick={() => archive(tier)}>Archive</button>
            </li>
          ))}
        </ul>
      )}
      <form className="tier-form" onSubmit={createTier}>
        <label>Tier name<input type="text" required maxLength={80} value={form.name} onChange={event => setForm({ ...form, name: event.target.value })} placeholder="Supporter" /></label>
        <label>Level<select value={form.level} onChange={event => setForm({ ...form, level: event.target.value })}>{[1, 2, 3, 4, 5].map(level => <option key={level} value={level}>{level}</option>)}</select></label>
        <label>Price per month (£)<input type="number" required min="0.99" max="499.99" step="0.01" value={form.price} onChange={event => setForm({ ...form, price: event.target.value })} placeholder="2.99" /></label>
        <label className="tier-form-wide">Description<input type="text" maxLength={500} value={form.description} onChange={event => setForm({ ...form, description: event.target.value })} placeholder="What members get" /></label>
        <fieldset className="tier-form-wide">
          <legend>Perks</legend>
          {Object.entries(PERK_LABELS).filter(([type]) => type !== 'CUSTOM').map(([type, label]) => (
            <label key={type} className="perk-option"><input type="checkbox" checked={form.perks.includes(type)} onChange={() => togglePerk(type)} />{label}</label>
          ))}
        </fieldset>
        <button type="submit" className="subscribe-button" disabled={saving}>{saving ? 'Creating…' : 'Add tier'}</button>
      </form>
      {error && <p className="subscription-error" role="alert">{error}</p>}
    </section>
  );
}
