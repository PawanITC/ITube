import { useEffect, useState } from 'react';
import VideoGrid from './VideoGrid';

function formatSubscriberCount(count) {
  return `${count} ${count === 1 ? 'subscriber' : 'subscribers'}`;
}

export default function ChannelPage({ channelId, onHome, onSelectVideo, onSelectChannel }) {
  const [channel, setChannel] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [subscriptionError, setSubscriptionError] = useState('');
  const [updatingSubscription, setUpdatingSubscription] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    fetch(`/api/channels/${channelId}`, { credentials: 'include' })
      .then(async response => {
        if (!response.ok) throw new Error(response.status === 404 ? 'Channel not found.' : 'Could not load this channel.');
        return response.json();
      })
      .then(details => { if (!cancelled) setChannel(details); })
      .catch(loadError => { if (!cancelled) setError(loadError.message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [channelId]);

  async function toggleSubscription() {
    if (!channel || updatingSubscription) return;
    setUpdatingSubscription(true);
    setSubscriptionError('');
    try {
      const response = await fetch(`/api/channels/${channelId}/subscriptions`, {
        method: channel.subscribed ? 'DELETE' : 'POST',
        credentials: 'include',
      });
      if (response.status === 401) throw new Error('Click Random user to create an account before subscribing.');
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail || 'Could not update your subscription.');
      }
      const status = await response.json();
      setChannel(current => ({ ...current, subscribed: status.subscribed, subscriberCount: status.subscriberCount }));
    } catch (subscribeError) {
      setSubscriptionError(subscribeError.message || 'Could not update your subscription.');
    } finally {
      setUpdatingSubscription(false);
    }
  }

  return (
    <main className="channel-page">
      <button type="button" className="watch-back" onClick={onHome}>‹ Back to videos</button>
      {loading ? <p className="channel-page-message">Loading channel…</p> : error ? <p className="channel-page-message" role="alert">{error}</p> : channel && (
        <>
          <header className="channel-banner">
            <span className="channel-page-avatar">{channel.channelName.trim().slice(0, 1).toUpperCase() || '?'}</span>
            <div className="channel-page-info"><h1>{channel.channelName}</h1><p>{formatSubscriberCount(channel.subscriberCount)}</p></div>
            {!channel.owner && <button type="button" className={`subscribe-button${channel.subscribed ? ' subscribed' : ''}`} onClick={toggleSubscription} disabled={updatingSubscription}>
              {updatingSubscription ? 'Updating…' : channel.subscribed ? 'Subscribed' : 'Subscribe'}
            </button>}
          </header>
          {subscriptionError && <p className="subscription-error" role="alert">{subscriptionError}</p>}
          <section className="channel-videos">
            <h2>Videos</h2>
            <VideoGrid videos={channel.videos} loading={false} onSelectVideo={onSelectVideo} onSelectChannel={onSelectChannel} />
          </section>
        </>
      )}
    </main>
  );
}
