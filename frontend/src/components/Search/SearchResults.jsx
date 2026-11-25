import VideoCard from '../Video/VideoCard.jsx';

export const SEARCH_FILTERS = [
  { id: 'all', label: 'All' },
  { id: 'videos', label: 'Videos' },
  { id: 'channels', label: 'Channels' },
  { id: 'playlists', label: 'Playlists' },
];

function ChannelResults({ channels, onSelectChannel }) {
  return (
    <section className="channel-search-results" aria-label="Channel results">
      {channels.map(channel => (
        <button type="button" className="channel-result" key={channel.channelId} onClick={() => onSelectChannel(channel.channelId)}>
          <span className="channel-result-avatar">{channel.channelName.trim().slice(0, 1).toUpperCase() || '?'}</span>
          <div><h2>{channel.channelName}</h2><p>Channel · #{channel.channelId}</p></div>
        </button>
      ))}
    </section>
  );
}

function VideoResults({ videos, onSelectVideo, onSelectChannel }) {
  return (
    <section className="video-grid" aria-label="Video results">
      {videos.map(video => <VideoCard key={video.videoId} video={video} onSelectVideo={onSelectVideo} onSelectChannel={onSelectChannel} />)}
    </section>
  );
}

function Empty({ title, query }) {
  return <div className="empty-state"><h2>{title}</h2><p>Nothing matched “{query}”. Try different keywords.</p></div>;
}

export default function SearchResults({
  query, filter, onFilterChange,
  videos, videosLoading, videosError,
  channels, channelsLoading, channelsError,
  onSelectVideo, onSelectChannel,
}) {
  const showVideos = filter === 'all' || filter === 'videos';
  const showChannels = filter === 'all' || filter === 'channels';
  const loading = (showVideos && videosLoading) || (showChannels && channelsLoading);
  // On "All", one failing source shouldn't hide results from the other.
  const errors = [showVideos && videosError, showChannels && channelsError].filter(Boolean);

  let body;
  if (filter === 'playlists') {
    body = <Empty title="No playlists found" query={query} />;
  } else if (loading) {
    body = <div className="empty-state"><p>Searching…</p></div>;
  } else {
    const channelList = showChannels && !channelsError ? channels : [];
    const videoList = showVideos && !videosError ? videos : [];
    if (!channelList.length && !videoList.length) {
      body = errors.length
        ? <div className="empty-state"><h2>Search unavailable</h2><p>{errors[0]}</p></div>
        : <Empty title={filter === 'videos' ? 'No videos found' : filter === 'channels' ? 'No channels found' : 'No results found'} query={query} />;
    } else {
      body = (
        <div className="search-results-body">
          {channelList.length > 0 && <ChannelResults channels={channelList} onSelectChannel={onSelectChannel} />}
          {videoList.length > 0 && <VideoResults videos={videoList} onSelectVideo={onSelectVideo} onSelectChannel={onSelectChannel} />}
          {errors.length > 0 && <p className="search-results-warning" role="status">Some results couldn’t be loaded: {errors[0]}</p>}
        </div>
      );
    }
  }

  return (
    <section className="search-results" aria-label={`Search results for ${query}`}>
      <h1 className="search-results-title">Results for “{query}”</h1>
      <div className="search-filters" role="tablist" aria-label="Filter search results">
        {SEARCH_FILTERS.map(option => (
          <button
            key={option.id}
            type="button"
            role="tab"
            aria-selected={filter === option.id}
            className={`search-filter-chip${filter === option.id ? ' is-active' : ''}`}
            onClick={() => onFilterChange(option.id)}
          >{option.label}</button>
        ))}
      </div>
      {body}
    </section>
  );
}
