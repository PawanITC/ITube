import { useEffect, useRef, useState } from 'react';
import { listLikedVideos, unlikeVideo } from '../api/likedVideos';
import VideoCard from '../components/Video/VideoCard';
import Icon from '../components/Icon';
import '../components/LikedVideos/LikedVideos.css';

const PAGE_SIZE = 20;
const formatDate = value => new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));

// App remounts the page when the session changes, clearing the previous account's data.
export default function LikedVideos({ onHome, onSelectVideo, onSelectChannel }) {
  const [entries, setEntries] = useState([]);
  const [page, setPage] = useState(0);
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
    listLikedVideos(page, PAGE_SIZE, controller.signal).then(result => {
      if (controller.signal.aborted) return;
      if (!result.length && page > 0) { setPage(previous => previous - 1); return; }
      setEntries(result);
      setLoading(false);
    }).catch(reason => {
      if (!controller.signal.aborted) { setError(reason.message); setLoading(false); }
    });
    return () => controller.abort();
  }, [page, revision]);

  async function remove(entry) {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setNotice('');
    try {
      await unlikeVideo(entry.video.videoId);
      if (alive.current) { setNotice(`Removed ${entry.video.title} from liked videos.`); setRevision(value => value + 1); }
    } catch (reason) { if (alive.current) setError(reason.message); }
    finally { pending.current = false; if (alive.current) setBusy(false); }
  }

  return <section className="liked-page" aria-labelledby="liked-heading">
    <header className="liked-heading"><div><p className="liked-eyebrow">YOUR LIBRARY</p><h1 id="liked-heading">Liked videos</h1><p>Keep your favourites close. Your most recent likes appear first.</p></div><button className="liked-button" disabled={loading || busy} onClick={() => { setNotice(''); setRevision(value => value + 1); }}>Refresh</button></header>
    <p className="liked-notice" role="status">{notice}</p>
    {error ? <div className="liked-error" role="alert"><p>{error}</p><button className="liked-button" onClick={() => setRevision(value => value + 1)}>Retry</button></div> : loading ? <p role="status">Loading liked videos…</p> : entries.length === 0 ? <div className="liked-empty"><Icon name="liked" size={48} /><h2>No liked videos yet</h2><p>Like a video while watching to find it here later.</p><a className="liked-button" href="/" onClick={event => { if (onHome) { event.preventDefault(); onHome(); } }}>Explore videos</a></div> : <>
      <ul className="liked-grid" aria-label="Liked videos">{entries.map(entry => <li key={entry.video.videoId}><VideoCard video={entry.video} onSelectVideo={id => onSelectVideo(id, entry.video)} onSelectChannel={onSelectChannel} /><div className="liked-entry-actions"><time dateTime={entry.likedAt}>Liked {formatDate(entry.likedAt)}</time><button className="liked-remove" disabled={loading || busy} aria-label={`Unlike ${entry.video.title}`} onClick={() => remove(entry)}>Unlike</button></div></li>)}</ul>
      <nav className="liked-pagination" aria-label="Liked video pages"><button className="liked-button" disabled={loading || busy || page === 0} onClick={() => { setNotice(''); setPage(value => value - 1); }}>Previous</button><span>Page {page + 1}</span><button className="liked-button" disabled={loading || busy || entries.length < PAGE_SIZE} onClick={() => { setNotice(''); setPage(value => value + 1); }}>Next</button></nav>
    </>}
  </section>;
}
