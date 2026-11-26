import { useEffect, useRef, useState } from 'react';
import { listPlaylists, createPlaylist, addPlaylistVideo } from '../../api/playlists';
import Icon from '../Icon';
import '../LikedVideos/LikedVideos.css';
import './Playlists.css';

// Like SaveButton, this is keyed by account and video in WatchPage.
export default function AddToPlaylistButton({ videoId, signedIn = true, onSignIn }) {
  const [open, setOpen] = useState(false);
  const [playlists, setPlaylists] = useState([]);
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState('');
  const [title, setTitle] = useState('');
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [revision, setRevision] = useState(0);
  const [askSignIn, setAskSignIn] = useState(false);
  const alive = useRef(false);
  const pending = useRef(false);
  const trigger = useRef(null);

  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    setLoading(true);
    setError('');
    listPlaylists(page, 20, controller.signal).then(result => {
      if (controller.signal.aborted) return;
      setPlaylists(result);
      setSelected(previous => result.some(item => item.id === previous) ? previous : result[0]?.id || '');
      setLoading(false);
    }).catch(reason => {
      if (!controller.signal.aborted) { setError(reason.message); setLoading(false); }
    });
    return () => controller.abort();
  }, [open, page, revision]);

  async function add(create = false) {
    if (pending.current || loading) return;
    pending.current = true;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      let playlist = playlists.find(item => item.id === selected);
      if (create) {
        playlist = await createPlaylist(title.trim());
        if (!alive.current) return;
        // If adding fails, keep the created playlist selectable so a retry won't duplicate it.
        setPlaylists(items => [playlist, ...items]);
        setSelected(playlist.id);
        setTitle('');
      }
      if (!playlist) return;
      await addPlaylistVideo(playlist.id, videoId);
      if (alive.current) {
        setNotice(`Added to ${playlist.title}.`);
        setOpen(false);
        trigger.current?.focus();
      }
    } catch (reason) { if (alive.current) setError(reason.message); }
    finally { pending.current = false; if (alive.current) setBusy(false); }
  }

  return <div className="playlist-control">
    <button ref={trigger} type="button" className="liked-button" aria-expanded={open} onClick={() => { if (!signedIn) { setAskSignIn(true); return; } setOpen(value => !value); setNotice(''); }} disabled={busy}>
      <Icon name="playlist" size={20} />Add to playlist
    </button>
    {askSignIn && <div className="liked-error" role="alert"><span>Sign in to use this feature</span> <button type="button" onClick={onSignIn}>Sign In</button></div>}
    <span className="liked-sr-only" role="status">{notice}</span>
    {open && signedIn && <section className="playlist-picker" aria-label="Choose playlist">
      <h2>Add to playlist</h2><p>Your playlists are private.</p>
      {error && <div className="liked-error" role="alert">{error} <button type="button" onClick={() => setRevision(value => value + 1)}>Retry</button></div>}
      {loading ? <p role="status">Loading playlists…</p> : !error && <>
        {playlists.length ? <><label>Playlist<select value={selected} onChange={event => setSelected(event.target.value)} disabled={busy}>{playlists.map(item => <option key={item.id} value={item.id}>{item.title}</option>)}</select></label>
          <button type="button" className="liked-button" disabled={busy || !selected} onClick={() => add()}>Add video</button></> : <p>No playlists yet. Create one below.</p>}
        <div className="playlist-actions"><button type="button" className="liked-button" disabled={busy || page === 0} onClick={() => setPage(value => value - 1)}>Previous playlists</button><button type="button" className="liked-button" disabled={busy || playlists.length < 20} onClick={() => setPage(value => value + 1)}>More playlists</button></div>
        <form className="playlist-form" onSubmit={event => { event.preventDefault(); add(true); }}><label>New playlist name<input value={title} onChange={event => setTitle(event.target.value)} maxLength={100} required disabled={busy} /></label><button className="liked-button" disabled={busy || !title.trim()}>Create and add</button></form>
      </>}
      <button type="button" className="liked-button" disabled={busy} onClick={() => { setOpen(false); trigger.current?.focus(); }}>Close</button>
    </section>}
  </div>;
}
