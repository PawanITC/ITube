import { useEffect, useRef, useState } from 'react';
import { getLikeStatus, likeVideo, unlikeVideo } from '../../api/likedVideos';
import Icon from '../Icon';
import './LikedVideos.css';

// The parent keys this component by video and session so state never crosses accounts/videos.
// Signed out, there is no status request; clicking asks the viewer to sign in.
export default function LikeButton({ videoId, signedIn = true, onSignIn }) {
  const [liked, setLiked] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const [notice, setNotice] = useState('');
  const [askSignIn, setAskSignIn] = useState(false);
  const alive = useRef(false);
  const pending = useRef(false);

  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => {
    if (!signedIn) { setLoading(false); setError(''); return undefined; }
    const controller = new AbortController();
    setLoading(true);
    setError('');
    getLikeStatus(videoId, controller.signal).then(result => {
      if (!controller.signal.aborted) { setLiked(result.liked); setLoading(false); }
    }).catch(reason => {
      if (!controller.signal.aborted) { setError(reason.message); setLoading(false); }
    });
    return () => controller.abort();
  }, [videoId, revision, signedIn]);

  async function toggle() {
    if (!signedIn) { setAskSignIn(true); return; }
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
    } catch (reason) { if (alive.current) { if (reason.status === 401) setAskSignIn(true); else setError(reason.message); } }
    finally { pending.current = false; if (alive.current) setBusy(false); }
  }

  return <div className="like-control">
    <button type="button" className={`liked-button${liked ? ' is-liked' : ''}`} aria-label={liked ? 'Unlike video' : 'Like video'} aria-pressed={liked} disabled={signedIn && (loading || busy || !!error)} onClick={toggle}>
      <Icon name="liked" size={20} />{signedIn && loading ? 'Loading…' : busy ? 'Saving…' : liked ? 'Liked' : 'Like'}
    </button>
    {error && <div className="liked-error" role="alert"><span>{error}</span> <button type="button" onClick={() => setRevision(value => value + 1)}>Retry</button></div>}
    {askSignIn && <div className="liked-error" role="alert"><span>Sign in to use this feature</span> <button type="button" onClick={onSignIn}>Sign In</button></div>}
    <span className="liked-sr-only" role="status">{notice}</span>
  </div>;
}
