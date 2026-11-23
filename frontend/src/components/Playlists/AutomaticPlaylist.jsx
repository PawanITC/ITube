import { useEffect, useState } from 'react';
import { getAutomaticPlaylist } from '../../api/playlists';
import Icon from '../Icon';
import './Playlists.css';

export default function AutomaticPlaylist({ onOpenPlaylist, onSelectVideo }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError('');
    setData(null);
    getAutomaticPlaylist(revision > 0, controller.signal).then(result => {
      if (controller.signal.aborted) return;
      if (!result?.playlist || !Array.isArray(result.videos)) throw new Error('Recommendations are temporarily unavailable.');
      setData(result);
      setLoading(false);
    }).catch(reason => {
      if (!controller.signal.aborted) { setError(reason.message); setLoading(false); }
    });
    return () => controller.abort();
  }, [revision]);

  const first = data?.videos.find(entry => entry.video);
  const id = data ? `automatic:${data.playlist.id}` : '';
  return <section className="automatic-playlist" aria-labelledby="automatic-playlist-heading">
    <div><Icon name="playlist" size={32} /><h2 id="automatic-playlist-heading">Made for you</h2></div>
    {loading ? <p role="status">Finding videos for you…</p> : error ? <div role="alert" className="liked-error"><p>{error}</p><button className="liked-button" onClick={() => setRevision(value => value + 1)}>Retry recommendations</button></div>
      : <><h3>For you</h3><p>{data.description}</p>
        {first ? <><p>{data.videos.length} recommended videos · Generated automatically</p><div className="playlist-actions"><button className="liked-button" onClick={() => onSelectVideo?.(first.videoId, first.video, id)}><Icon name="play" size={20} />Play your mix</button><button className="liked-button" onClick={() => onOpenPlaylist?.(id)}>View your mix</button></div></>
          : <p>No new videos are available yet. Your mix will grow as more public videos become available.</p>}
        <button className="liked-button" onClick={() => setRevision(value => value + 1)}>Refresh recommendations</button>
      </>}
  </section>;
}
