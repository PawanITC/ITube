import './Playlists.css';

export default function PlaylistQueue({ playlistId, videoId, queue, onSelectVideo, onOpenPlaylist }) {
  const open = entry => onSelectVideo(entry.videoId, entry.video, playlistId);
  return <section className="playlist-queue" aria-label="Playlist playback">
    <h2>{queue.playlist?.title || 'Playlist'}</h2>
    <button type="button" className="liked-button" onClick={() => onOpenPlaylist?.(playlistId)}>Back to playlist</button>
    {queue.error ? <div role="alert" className="liked-error">{queue.error} <button type="button" onClick={queue.retry}>Retry playlist</button></div>
      : queue.loading ? <p role="status">Loading playlist…</p> : <>
        <div className="playlist-actions"><button type="button" className="liked-button" disabled={!queue.previous} onClick={() => open(queue.previous)}>Previous video</button><button type="button" className="liked-button" disabled={!queue.next} onClick={() => open(queue.next)}>Next video</button></div>
        <ol>{queue.videos.map(entry => <li key={entry.videoId}>{entry.video ? <button type="button" aria-current={entry.videoId === videoId ? 'true' : undefined} onClick={() => open(entry)}>{entry.video.title}</button> : <span>Video unavailable</span>}</li>)}</ol>
      </>}
  </section>;
}
