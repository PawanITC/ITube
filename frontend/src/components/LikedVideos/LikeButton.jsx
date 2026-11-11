import { useEffect, useRef, useState } from 'react';
import { getLikeStatus, likeVideo, unlikeVideo } from '../../api/likedVideos';
import Icon from '../Icon';
import './LikedVideos.css';

// The parent keys this component by video and session so state never crosses accounts/videos.
export default function LikeButton({ videoId }) {
  const [liked, setLiked] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const [notice, setNotice] = useState('');
  const alive = useRef(false);
  const pending = useRef(false);

  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError('');
    getLikeStatus(videoId, controller.signal).then(result => {
      if (!controller.signal.aborted) { setLiked(result.liked); setLoading(false); }
    }).catch(reason => {
      if (!controller.signal.aborted) { setError(reason.message); setLoading(false); }
    });
    return () => controller.abort();
  }, [videoId, revision]);

  async function toggle() {
    if (pending.current || loading || error) return;
    pending.current = true;
    setBusy(true);
    setNotice('');
    try {
      await (liked ? unlikeVideo(videoId) : likeVideo(videoId));
      if (alive.current) {
        setLiked(!liked);
        setNotice(liked ? 'Removed from liked videos.' : 'Added to liked videos.');
      }
    } catch (reason) { if (alive.current) setError(reason.message); }
    finally { pending.current = false; if (alive.current) setBusy(false); }
  }

  return <div className="like-control">
    <button type="button" className={`liked-button${liked ? ' is-liked' : ''}`} aria-label={liked ? 'Unlike video' : 'Like video'} aria-pressed={liked} disabled={loading || busy || !!error} onClick={toggle}>
      <Icon name="liked" size={20} />{loading ? 'Loading…' : busy ? 'Saving…' : liked ? 'Liked' : 'Like'}
    </button>
    {error && <div className="liked-error" role="alert"><span>{error}</span> <button type="button" onClick={() => setRevision(value => value + 1)}>Retry</button></div>}
    <span className="liked-sr-only" role="status">{notice}</span>
  </div>;
}
