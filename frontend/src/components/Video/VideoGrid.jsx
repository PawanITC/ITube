import VideoCard from './VideoCard.jsx';
import Icon from '../Icon.jsx';

export default function VideoGrid({ videos, loading, error, onSelectVideo, onSelectChannel, showFeatured = false }) {
  if (loading) return <div className="empty-state"><p>Loading videos…</p></div>;
  if (error) return <div className="empty-state"><h2>Videos unavailable</h2><p>{error}</p></div>;
  if (!videos.length) return <div className="empty-state"><h2>No videos yet</h2><p>Uploaded videos will appear here.</p></div>;
  const featured = showFeatured ? videos[0] : null;
  const gridVideos = featured ? videos.slice(1) : videos;

  return (
    <>
      {featured && (
        <article className="featured-video">
          <div className="featured-copy">
            <p className="featured-label">Featured video</p>
            <h2>{featured.title}</h2>
            <a className="featured-channel" href={`/channel/${featured.channelId}`}
              onClick={event => { event.preventDefault(); onSelectChannel(featured.channelId); }}>{featured.channelName}</a>
            {featured.visibility === 'MEMBERS_ONLY' && <p className="featured-channel">Members only</p>}
            <a className="featured-action" href={`/watch/${featured.videoId}`} aria-label={`Play ${featured.title}`}
              onClick={event => { event.preventDefault(); onSelectVideo(featured.videoId); }}>
              <Icon name="play" size={20} /> Watch now
            </a>
          </div>
          <div className="featured-preview">
            <img src={featured.thumbnailUrl || '/thumbnail-placeholder.svg'} alt="" />
            {featured.duration && <span className="duration">{featured.duration}</span>}
          </div>
        </article>
      )}
      {featured && gridVideos.length > 0 && <h2 className="feed-section-title">More to watch</h2>}
      {gridVideos.length > 0 && (
        <section className="video-grid" aria-label="Videos">
          {gridVideos.map(video => <VideoCard key={video.videoId} video={video} onSelectVideo={onSelectVideo} onSelectChannel={onSelectChannel} />)}
        </section>
      )}
    </>
  );
}
