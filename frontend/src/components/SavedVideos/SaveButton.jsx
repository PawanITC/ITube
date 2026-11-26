import { useEffect, useRef, useState } from 'react';
import { getSavedStatus, saveVideo, removeSavedVideo } from '../../api/savedVideos';
import Icon from '../Icon';
import '../LikedVideos/LikedVideos.css';

// WatchPage keys this by video and account, keeping each status request in its own session.
// Signed out, there is no status request; clicking asks the viewer to sign in.
export default function SaveButton({ videoId, signedIn = true, onSignIn }) {
  const [saved, setSaved] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [revision, setRevision] = useState(0);
  const [askSignIn, setAskSignIn] = useState(false);
  const alive = useRef(false);
  const pending = useRef(false);

  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => {
    if (!signedIn) { setLoading(false); setError(''); return undefined; }
    const controller = new AbortController();
    setLoading(true);
    setError('');
    getSavedStatus(videoId, controller.signal).then(result => {
      if (!controller.signal.aborted) { setSaved(result.saved); setLoading(false); }
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
      await (saved ? removeSavedVideo(videoId) : saveVideo(videoId));
      if (alive.current) {
        setSaved(!saved);
        setNotice(saved ? 'Removed from Watch later.' : 'Saved to Watch later.');
      }
    } catch (reason) { if (alive.current) { if (reason.status === 401) setAskSignIn(true); else setError(reason.message); } }
    finally { pending.current = false; if (alive.current) setBusy(false); }
  }

  return <div className="like-control">
    <button type="button" className={`liked-button${saved ? ' is-liked' : ''}`}
      aria-label={saved ? 'Remove video from Watch later' : 'Save video to Watch later'}
      aria-pressed={saved} disabled={signedIn && (loading || busy || !!error)} onClick={toggle}>
      <Icon name="later" size={20} />{signedIn && loading ? 'Loading…' : busy ? 'Saving…' : saved ? 'Saved' : 'Save'}
    </button>
    {error && <div className="liked-error" role="alert">{error} <button type="button" onClick={() => setRevision(value => value + 1)}>Retry</button></div>}
    {askSignIn && <div className="liked-error" role="alert"><span>Sign in to use this feature</span> <button type="button" onClick={onSignIn}>Sign In</button></div>}
    <span className="liked-sr-only" role="status">{notice}</span>
  </div>;
}
