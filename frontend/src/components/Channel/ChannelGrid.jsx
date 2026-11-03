export default function ChannelGrid({ channels, loading, error, query, onSelectChannel }) {
  if (loading) return <div className="empty-state"><p>Searching channels…</p></div>;
  if (error) return <div className="empty-state"><h2>Channel search unavailable</h2><p>{error}</p></div>;
  if (!channels.length) return <div className="empty-state"><h2>No channels found</h2><p>No channels matched “{query}”.</p></div>;

  return (
    <section className="channel-search-results" aria-label="Channel search results">
      {channels.map(channel => (
        <button type="button" className="channel-result" key={channel.channelId} onClick={() => onSelectChannel(channel.channelId)}>
          <span className="channel-result-avatar">{channel.channelName.trim().slice(0, 1).toUpperCase() || '?'}</span>
          <div><h2>{channel.channelName}</h2><p>Channel · #{channel.channelId}</p></div>
        </button>
      ))}
    </section>
  );
}
