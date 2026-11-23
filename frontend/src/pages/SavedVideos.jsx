import { useEffect, useRef, useState } from 'react';
import { listSavedVideos, removeSavedVideo, clearSavedVideos } from '../api/savedVideos';
import VideoCard from '../components/Video/VideoCard';
import Icon from '../components/Icon';
import '../components/LikedVideos/LikedVideos.css';
import './SavedVideos.css';

const PAGE_SIZE = 20;
const dateFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

// App remounts this page when the account changes, so saved lists never cross sessions.
export default function SavedVideos({ onHome, onSelectVideo, onSelectChannel }) {
  const [entries, setEntries] = useState([]);
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [confirmClear, setConfirmClear] = useState(false);
  const alive = useRef(false);
  const pending = useRef(false);
  const cancelButton = useRef(null);
  const clearButton = useRef(null);

  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => { if (confirmClear) cancelButton.current?.focus(); }, [confirmClear]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError('');
    listSavedVideos(page, PAGE_SIZE, controller.signal).then(result => {
      if (controller.signal.aborted) return;
      if (!result.length && page > 0) { setPage(value => value - 1); return; }
      setEntries(result);
      setLoading(false);
    }).catch(reason => {
      if (!controller.signal.aborted) { setError(reason.message); setLoading(false); }
    });
    return () => controller.abort();
  }, [page, revision]);

  async function mutate(operation, message, clear = false) {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await operation();
      if (alive.current) {
        setConfirmClear(false);
        setNotice(message);
        if (clear) setPage(0);
        setRevision(value => value + 1);
      }
    } catch (reason) { if (alive.current) setError(reason.message); }
    finally { pending.current = false; if (alive.current) setBusy(false); }
  }

  return <section className="liked-page saved-page" aria-labelledby="saved-heading">
    <header className="liked-heading">
      <div><p className="liked-eyebrow">YOUR LIBRARY</p><h1 id="saved-heading">Watch later</h1><p>Your saved videos, newest first. Watching a video keeps it in this list.</p></div>
      <div className="saved-toolbar">
        <button className="liked-button" disabled={loading || busy} onClick={() => { setNotice(''); setRevision(value => value + 1); }}>Refresh</button>
        <button ref={clearButton} className="liked-button" disabled={loading || busy || !entries.length} onClick={() => setConfirmClear(true)}>Clear saved videos</button>
      </div>
    </header>
    <p className="liked-notice" role="status">{notice}</p>
    {confirmClear && <div className="saved-confirm" role="group" aria-label="Clear Watch later confirmation">
      <p>Remove all videos from your Watch later list?</p>
      <button className="liked-button" disabled={busy} onClick={() => mutate(clearSavedVideos, 'Watch later cleared.', true)}>Confirm clear</button>
      <button ref={cancelButton} className="liked-button" disabled={busy} onClick={() => { setConfirmClear(false); clearButton.current?.focus(); }}>Cancel</button>
    </div>}
    {error ? <div className="liked-error" role="alert"><p>{error}</p><button className="liked-button" onClick={() => setRevision(value => value + 1)}>Retry</button></div>
      : loading ? <p role="status">Loading saved videos…</p>
      : !entries.length ? <div className="liked-empty"><Icon name="later" size={48} /><h2>No saved videos yet</h2><p>Use Save while watching a video to find it here later.</p><a className="liked-button" href="/" onClick={event => { if (onHome) { event.preventDefault(); onHome(); } }}>Explore videos</a></div>
      : <>
        <ul className="liked-grid" aria-label="Saved videos">{entries.map(entry => <li key={entry.videoId}>
          {entry.video ? <VideoCard video={entry.video} onSelectVideo={id => onSelectVideo?.(id, entry.video)} onSelectChannel={onSelectChannel} />
            : <article className="saved-unavailable"><Icon name="later" size={32} /><h2>Video unavailable</h2><p>This video may be private, deleted, or temporarily unavailable. You can still remove it.</p></article>}
          <div className="liked-entry-actions"><time dateTime={entry.savedAt}>Saved {dateFormatter.format(new Date(entry.savedAt))}</time>
            <button className="liked-remove" disabled={loading || busy} aria-label={`Remove ${entry.video?.title || 'unavailable video'} from Watch later`} title="Remove from Watch later"
              onClick={() => mutate(() => removeSavedVideo(entry.videoId), 'Video removed from Watch later.')}><Icon name="later" size={20} /></button>
          </div>
        </li>)}</ul>
        <nav className="liked-pagination" aria-label="Saved video pages"><button className="liked-button" disabled={loading || busy || page === 0} onClick={() => setPage(value => value - 1)}>Previous</button><span>Page {page + 1}</span><button className="liked-button" disabled={loading || busy || entries.length < PAGE_SIZE} onClick={() => setPage(value => value + 1)}>Next</button></nav>
      </>}
  </section>;
}
