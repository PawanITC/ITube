import { useEffect, useState } from 'react';
import { loadPlaylistQueue } from '../../api/playlists';

export default function usePlaylistQueue(playlistId, videoId) {
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    setResult(null);
    setError('');
    if (!playlistId) { setLoading(false); return; }
    const controller = new AbortController();
    setLoading(true);
    loadPlaylistQueue(playlistId, controller.signal).then(value => {
      if (!controller.signal.aborted) { setResult(value); setLoading(false); }
    }).catch(reason => {
      if (!controller.signal.aborted) { setError(reason.message); setLoading(false); }
    });
    return () => controller.abort();
  }, [playlistId, videoId, revision]);

  const videos = result?.videos || [];
  const index = videos.findIndex(entry => entry.videoId === videoId);
  const previous = index < 0 ? null : videos.slice(0, index).reverse().find(entry => entry.video);
  const next = index < 0 ? null : videos.slice(index + 1).find(entry => entry.video);
  return { playlist: result?.playlist, videos, previous, next, loading, error, retry: () => setRevision(value => value + 1) };
}
