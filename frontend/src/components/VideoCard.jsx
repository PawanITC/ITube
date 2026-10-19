import Icon from './Icon';

export default function VideoCard({ video, onSelectVideo, onSelectChannel }) {
  const initials = video.channelName?.trim().slice(0, 1).toUpperCase() || '?';
  function open(event) {
    event.preventDefault();
    onSelectVideo(video.videoId);
  }
  function openChannel(event) {
    event.preventDefault();
    event.stopPropagation();
    onSelectChannel(video.channelId);
  }
  return <article className="video-card"><a href={`/watch/${video.videoId}`} onClick={open} className="thumbnail-link" aria-label={`Play ${video.title}`}><img className="thumbnail" src={video.thumbnailUrl || '/thumbnail-placeholder.svg'} alt="" /><span className="duration">{video.duration}</span><span className="hover-play"><Icon name="play" size={28} /></span></a><div className="video-details"><span className="channel-avatar" style={{ background: '#537c9c' }}>{initials}</span><div className="video-copy"><a href={`/watch/${video.videoId}`} onClick={open} className="video-title">{video.title}</a><a href={`/channel/${video.channelId}`} onClick={openChannel} className="channel-name">{video.channelName}</a></div><button className="card-more" aria-label="More options"><Icon name="more" size={21} /></button></div></article>;
}
