import { useEffect, useRef, useState } from 'react';
import { getSavedStatus, saveVideo, removeSavedVideo } from '../../api/savedVideos';
import Icon from '../Icon';
import '../LikedVideos/LikedVideos.css';

// WatchPage keys this by video and account, keeping each status request in its own session.
export default function SaveButton({ videoId }) {
  const [saved, setSaved] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [revision, setRevision] = useState(0);
  const alive = useRef(false);
  const pending = useRef(false);

  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError('');
    getSavedStatus(videoId, controller.signal).then(result => {
      if (!controller.signal.aborted) { setSaved(result.saved); setLoading(false); }
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
      await (saved ? removeSavedVideo(videoId) : saveVideo(videoId));
      if (alive.current) {
        setSaved(!saved);
        setNotice(saved ? 'Removed from Watch later.' : 'Saved to Watch later.');
      }
    } catch (reason) { if (alive.current) setError(reason.message); }
    finally { pending.current = false; if (alive.current) setBusy(false); }
  }

  return <div className="like-control">
    <button type="button" className={`liked-button${saved ? ' is-liked' : ''}`}
      aria-label={saved ? 'Remove video from Watch later' : 'Save video to Watch later'}
      aria-pressed={saved} disabled={loading || busy || !!error} onClick={toggle}>
      <Icon name="later" size={20} />{loading ? 'Loading…' : busy ? 'Saving…' : saved ? 'Saved' : 'Save'}
    </button>
    {error && <div className="liked-error" role="alert">{error} <button type="button" onClick={() => setRevision(value => value + 1)}>Retry</button></div>}
    <span className="liked-sr-only" role="status">{notice}</span>
  </div>;
}
