import { useEffect, useState } from 'react';

const visibilityLabels = {
  PUBLIC: 'Public',
  UNLISTED: 'Unlisted',
  PRIVATE: 'Private',
  MEMBERS_ONLY: 'Members only',
};

export default function MyVideosPage({ onEditVideo }) {
  const [videos, setVideos] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    fetch('/api/videos/mine', { credentials: 'include' })
      .then(response => {
        if (response.status === 401) throw new Error('Click the ITube logo to start a session to see your videos.');
        if (!response.ok) throw new Error('Could not load your videos.');
        return response.json();
      })
      .then(items => { if (!cancelled) { setVideos(items); setError(''); } })
      .catch(loadError => { if (!cancelled) setError(loadError.message || 'Could not load your videos.'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  function open(event, videoId) {
    event.preventDefault();
    onEditVideo(videoId);
  }

  return (
    <div className="feed-content my-videos-page">
      <h1 className="my-videos-heading">My Videos</h1>
      {loading ? <div className="empty-state"><p>Loading your videos…</p></div>
        : error ? <div className="empty-state"><h2>Videos unavailable</h2><p>{error}</p></div>
        : videos.length === 0 ? <div className="empty-state"><h2>No videos yet</h2><p>Videos you upload with Create will appear here.</p></div>
        : (
          <section className="video-grid" aria-label="My videos">
            {videos.map(video => (
              <article className="video-card" key={video.videoId}>
                <a href={`/my-videos/${video.videoId}`} onClick={event => open(event, video.videoId)} className="thumbnail-link" aria-label={`Edit ${video.title}`}>
                  <img className="thumbnail" src={video.thumbnailUrl || '/thumbnail-placeholder.svg'} alt="" />
                  <span className={`visibility-badge visibility-${video.visibility.toLowerCase()}`}>{visibilityLabels[video.visibility] || video.visibility}</span>
                  <span className="duration">{video.duration}</span>
                </a>
                <div className="video-details">
                  <div className="video-copy">
                    <a href={`/my-videos/${video.videoId}`} onClick={event => open(event, video.videoId)} className="video-title">{video.title}</a>
                    <span className="channel-name">{video.channelName}{video.processingStatus !== 'READY' ? ` · ${video.processingStatus.toLowerCase()}` : ''}</span>
                  </div>
                </div>
              </article>
            ))}
          </section>
        )}
    </div>
  );
}
