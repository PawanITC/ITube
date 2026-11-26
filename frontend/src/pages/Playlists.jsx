import { useEffect, useRef, useState } from 'react';
import { listPlaylists, getPlaylist, createPlaylist, renamePlaylist, deletePlaylist, removePlaylistVideo, movePlaylistVideo, loadPlaylistQueue } from '../api/playlists';
import Icon from '../components/Icon';
import AutomaticPlaylist from '../components/Playlists/AutomaticPlaylist';
import VideoCard from '../components/Video/VideoCard';
import '../components/LikedVideos/LikedVideos.css';
import '../components/Playlists/Playlists.css';

const PAGE_SIZE = 20;
const SIGN_IN_MESSAGE = 'Sign in to use this feature';

// App keys by owner AND playlist id; navigation clears old requests, pages and form state.
export default function Playlists({ playlistId, onOpenPlaylist, onHome, onSelectVideo, onSelectChannel, onSignIn, signedIn = true }) {
  const automatic = playlistId?.startsWith('automatic:');
  const [data, setData] = useState(null);
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [name, setName] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [signedOut, setSignedOut] = useState(false);
  const [notice, setNotice] = useState('');
  const [confirmDelete, setConfirmDelete] = useState(false);
  const alive = useRef(false);
  const pending = useRef(false);
  const cancelButton = useRef(null);

  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => { if (confirmDelete) cancelButton.current?.focus(); }, [confirmDelete]);
  useEffect(() => {
    // signedIn is null while App is still checking the session; false means nobody is signed in.
    if (signedIn === null) return undefined;
    if (!signedIn) { setSignedOut(true); setError(SIGN_IN_MESSAGE); setLoading(false); return undefined; }
    const controller = new AbortController();
    setLoading(true);
    setError('');
    const operation = playlistId ? getPlaylist(playlistId, page, PAGE_SIZE, controller.signal) : listPlaylists(page, PAGE_SIZE, controller.signal);
    operation.then(result => {
      if (controller.signal.aborted) return;
      const entries = playlistId ? result.videos : result;
      if (!entries.length && page > 0) { setPage(value => value - 1); return; }
      setData(result);
      if (playlistId) setName(result.playlist.title);
      setLoading(false);
    }).catch(reason => {
      if (!controller.signal.aborted) { if (reason.status === 401) { setSignedOut(true); setError(SIGN_IN_MESSAGE); } else setError(reason.message); setLoading(false); }
    });
    return () => controller.abort();
  }, [playlistId, page, revision, signedIn]);

  async function mutate(operation, message, after) {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await operation();
      if (alive.current) {
        after?.(result);
        setNotice(message);
        setConfirmDelete(false);
        setRevision(value => value + 1);
      }
    } catch (reason) { if (alive.current) { if (reason.status === 401) { setSignedOut(true); setError(SIGN_IN_MESSAGE); } else setError(reason.message); } }
    finally { pending.current = false; if (alive.current) setBusy(false); }
  }

  async function playAll() {
    await mutate(() => loadPlaylistQueue(playlistId), '', result => {
      const first = result.videos.find(entry => entry.video);
      if (first) onSelectVideo?.(first.videoId, first.video, playlistId);
      else throw new Error('No videos in this playlist are currently available.');
    });
  }

  const details = playlistId ? data : null;
  const list = playlistId ? data?.videos || [] : data || [];
  const disabled = loading || busy || signedOut;
  return <section className="liked-page playlist-page" aria-labelledby="playlists-heading">
    <header className="liked-heading"><div><p className="liked-eyebrow">YOUR LIBRARY · PRIVATE</p><h1 id="playlists-heading">{details?.playlist.title || 'Playlists'}</h1><p>{playlistId ? `${details?.playlist.videoCount || 0} videos · Up to 500 per playlist` : 'Organise videos into your own named playlists.'}</p></div>
      <div className="playlist-actions">{signedOut ? <button className="liked-button" onClick={onSignIn}>Sign In</button> : <button className="liked-button" disabled={disabled} onClick={() => setRevision(value => value + 1)}>Refresh</button>}{playlistId && <button className="liked-button" onClick={() => onOpenPlaylist?.()}>All playlists</button>}</div>
    </header>
    <p role="status" className="liked-notice">{notice}</p>
    {!playlistId && signedIn && !signedOut && <AutomaticPlaylist onOpenPlaylist={onOpenPlaylist} onSelectVideo={onSelectVideo} />}
    {error && <div className="liked-error" role="alert">{error} {!signedOut && <button type="button" onClick={() => setRevision(value => value + 1)}>Retry</button>}</div>}
    {!playlistId && <form className="playlist-form" onSubmit={event => { event.preventDefault(); mutate(() => createPlaylist(name.trim()), 'Playlist created.', value => { setName(''); onOpenPlaylist?.(value.id); }); }}>
      <label>Playlist name<input maxLength={100} required value={name} disabled={disabled} onChange={event => setName(event.target.value)} /></label><button className="liked-button" disabled={disabled || !name.trim()}>Create playlist</button>
    </form>}
    {loading ? <p role="status">Loading playlists…</p> : details ? <>
      {!automatic && <form className="playlist-form" onSubmit={event => { event.preventDefault(); mutate(() => renamePlaylist(playlistId, name.trim()), 'Playlist renamed.'); }}>
        <label>Playlist name<input maxLength={100} required value={name} disabled={disabled} onChange={event => setName(event.target.value)} /></label><button className="liked-button" disabled={disabled || !name.trim() || name.trim() === details.playlist.title}>Rename playlist</button>
      </form>}
      {automatic && <p>This mix is generated automatically. Open All playlists to refresh your recommendations.</p>}
      <div className="playlist-actions"><button className="liked-button" disabled={disabled || !details.playlist.videoCount} onClick={playAll}><Icon name="play" size={20} />Play playlist</button>{!automatic && <button className="liked-button" disabled={disabled} onClick={() => setConfirmDelete(true)}>Delete playlist</button>}</div>
      {confirmDelete && <div className="playlist-confirm" role="group" aria-label="Delete playlist confirmation"><p>Delete this playlist? The videos themselves will remain.</p><button className="liked-button" disabled={busy} onClick={() => mutate(() => deletePlaylist(playlistId), 'Playlist deleted.', () => onOpenPlaylist?.())}>Confirm delete</button><button ref={cancelButton} className="liked-button" disabled={busy} onClick={() => setConfirmDelete(false)}>Cancel</button></div>}
      {!list.length ? <div className="liked-empty"><Icon name="playlist" size={48} /><h2>{automatic ? 'No recommendations available' : 'This playlist is empty'}</h2><p>{automatic ? 'Open All playlists to generate a fresh mix.' : 'Use Add to playlist while watching a video.'}</p><button className="liked-button" onClick={onHome}>Explore videos</button></div>
        : <ul className="liked-grid" aria-label="Playlist videos">{list.map(entry => {
          const label = entry.video?.title || `unavailable video ${entry.position + 1}`;
          return <li key={entry.videoId}>{entry.video ? <VideoCard video={entry.video} onSelectVideo={id => onSelectVideo?.(id, entry.video, playlistId)} onSelectChannel={onSelectChannel} /> : <article className="playlist-unavailable"><Icon name="playlist" size={32} /><h2>Video unavailable</h2><p>You can still move or remove this entry.</p></article>}
            <div className="playlist-entry-actions"><span>#{entry.position + 1}</span>
              {!automatic && <>
              <button className="liked-button" disabled={disabled || entry.position === 0} aria-label={`Move ${label} up`} onClick={() => mutate(() => movePlaylistVideo(playlistId, entry.videoId, entry.position - 1, details.playlist.version), 'Playlist order updated.')}>↑</button>
              <button className="liked-button" disabled={disabled || entry.position === details.playlist.videoCount - 1} aria-label={`Move ${label} down`} onClick={() => mutate(() => movePlaylistVideo(playlistId, entry.videoId, entry.position + 1, details.playlist.version), 'Playlist order updated.')}>↓</button>
              <button className="liked-remove" disabled={disabled} aria-label={`Remove ${label} from playlist`} onClick={() => mutate(() => removePlaylistVideo(playlistId, entry.videoId), 'Video removed from playlist.')}>Remove</button>
              </>}
            </div>
          </li>;
        })}</ul>}
    </> : !playlistId && !error && (list.length ? <ul className="playlist-grid" aria-label="Your playlists">{list.map(item => <li key={item.id}><Icon name="playlist" size={40} /><h2><a href={`/playlists/${item.id}`} onClick={event => { if (onOpenPlaylist) { event.preventDefault(); onOpenPlaylist(item.id); } }}>{item.title}</a></h2><p>{item.videoCount} videos · Private</p></li>)}</ul> : <div className="liked-empty"><Icon name="playlist" size={48} /><h2>No playlists yet</h2><p>Create your first playlist above, then add videos from the player.</p></div>)}
    {!loading && data && <nav className="liked-pagination" aria-label="Playlist pages"><button className="liked-button" disabled={disabled || page === 0} onClick={() => setPage(value => value - 1)}>Previous</button><span>Page {page + 1}</span><button className="liked-button" disabled={disabled || (playlistId ? !details.hasMore : list.length < PAGE_SIZE)} onClick={() => setPage(value => value + 1)}>Next</button></nav>}
  </section>;
}
