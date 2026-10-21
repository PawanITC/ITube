import { useEffect, useMemo, useState } from 'react';
import Header from './components/Header';
import Sidebar from './components/Sidebar';
import VideoGrid from './components/VideoGrid';
import UploadVideoPage from './components/UploadVideoPage';
import WatchPage from './components/WatchPage';
import ChannelGrid from './components/ChannelGrid';
import ChannelPage from './components/ChannelPage';
import './App.css';

function readRoute() {
  const watchMatch = window.location.pathname.match(/^\/watch\/(\d+)$/);
  if (watchMatch) return { page: 'watch', videoId: Number(watchMatch[1]) };
  const channelMatch = window.location.pathname.match(/^\/channel\/(\d+)$/);
  if (channelMatch) return { page: 'channel', channelId: Number(channelMatch[1]) };
  if (window.location.pathname === '/upload') return { page: 'upload', videoId: null };
  return { page: 'home', videoId: null };
}

function App() {
  const [route, setRoute] = useState(readRoute);
  const [uploadSession, setUploadSession] = useState(null);
  const [activeSession, setActiveSession] = useState(null);
  const [search, setSearch] = useState('');
  const [videos, setVideos] = useState([]);
  const [videosLoading, setVideosLoading] = useState(true);
  const [videosError, setVideosError] = useState('');
  const [searchMode, setSearchMode] = useState('videos');
  const [channelResults, setChannelResults] = useState([]);
  const [channelsLoading, setChannelsLoading] = useState(false);
  const [channelsError, setChannelsError] = useState('');

  useEffect(() => {
    if (route.page === 'upload') return;
    let cancelled = false;
    setVideosLoading(true);
    fetch('/api/videos')
      .then(response => {
        if (!response.ok) throw new Error('Could not load videos from the server.');
        return response.json();
      })
      .then(databaseVideos => {
        if (!cancelled) {
          setVideos(databaseVideos);
          setVideosError('');
        }
      })
      .catch(error => {
        if (!cancelled) setVideosError(error.message || 'Could not load videos from the server.');
      })
      .finally(() => {
        if (!cancelled) setVideosLoading(false);
      });
    return () => { cancelled = true; };
  }, [route.page]);

  const visibleVideos = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return videos.filter(video => !needle || `${video.title} ${video.channelName}`.toLowerCase().includes(needle));
  }, [search, videos]);

  async function handleSearch(query, type) {
    setSearch(query);
    setSearchMode(type);
    setChannelsError('');
    if (route.page !== 'home') {
      window.history.pushState({}, '', '/');
      setRoute({ page: 'home', videoId: null });
    }
    if (type === 'channels') {
      setChannelsLoading(true);
      try {
        const params = new URLSearchParams({ q: query });
        const response = await fetch(`/api/channels/search?${params}`);
        if (!response.ok) throw new Error('Could not search channels.');
        setChannelResults(await response.json());
      } catch (error) {
        setChannelsError(error.message || 'Could not search channels.');
        setChannelResults([]);
      } finally {
        setChannelsLoading(false);
      }
    }
  }

  useEffect(() => {
    function syncPageFromLocation() {
      setRoute(readRoute());
    }
    window.addEventListener('popstate', syncPageFromLocation);
    return () => window.removeEventListener('popstate', syncPageFromLocation);
  }, []);

  function openUploadPage(session) {
    setUploadSession(session);
    window.history.pushState({}, '', '/upload');
    setRoute({ page: 'upload', videoId: null });
  }

  function returnHome() {
    window.history.pushState({}, '', '/');
    setRoute({ page: 'home', videoId: null });
  }

  function openVideo(videoId) {
    window.history.pushState({}, '', `/watch/${videoId}`);
    setRoute({ page: 'watch', videoId });
  }

  function openChannel(channelId) {
    window.history.pushState({}, '', `/channel/${channelId}`);
    setRoute({ page: 'channel', channelId });
  }

  return (
    <div className="app-shell" id="home">
      <Header onSearch={handleSearch} onCreatePage={openUploadPage} onSessionStarted={setActiveSession} onSelectChannel={openChannel} />
      {route.page === 'upload' ? (
        <main className="upload-main"><UploadVideoPage session={uploadSession} onCancel={returnHome} /></main>
      ) : route.page === 'watch' ? (
        <WatchPage videoId={route.videoId} videos={videos} videosLoading={videosLoading} videosError={videosError} session={activeSession} onSelectVideo={openVideo} onSelectChannel={openChannel} onHome={returnHome} />
      ) : route.page === 'channel' ? (
        <ChannelPage channelId={route.channelId} onHome={returnHome} onSelectVideo={openVideo} onSelectChannel={openChannel} />
      ) : (
        <><Sidebar session={activeSession} onSelectChannel={openChannel} /><main className="main-content"><div className="feed-content">{searchMode === 'channels' ? <ChannelGrid channels={channelResults} loading={channelsLoading} error={channelsError} query={search} onSelectChannel={openChannel} /> : <VideoGrid videos={visibleVideos} loading={videosLoading} error={videosError} onSelectVideo={openVideo} onSelectChannel={openChannel} />}</div></main></>
      )}
    </div>
  );
}

export default App;
