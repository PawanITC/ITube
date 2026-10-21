import VideoCard from './VideoCard';

export default function VideoGrid({ videos, loading, error, onSelectVideo, onSelectChannel }) {
  if (loading) return <div className="empty-state"><p>Loading videos…</p></div>;
  if (error) return <div className="empty-state"><h2>Videos unavailable</h2><p>{error}</p></div>;
  if (!videos.length) return <div className="empty-state"><h2>No videos yet</h2><p>Uploaded videos will appear here.</p></div>;
  return <section className="video-grid" aria-label="Videos">{videos.map(video => <VideoCard key={video.videoId} video={video} onSelectVideo={onSelectVideo} onSelectChannel={onSelectChannel} />)}</section>;
}
