import { Suspense, lazy, useEffect, useMemo, useState } from 'react';
import Header from './components/Header';
import History from './pages/History';
import LikedVideos from './pages/LikedVideos';
import Sidebar from './components/Sidebar';
import VideoGrid from './components/Video/VideoGrid';
import UploadVideoPage from './components/Video/UploadVideoPage';
import MyVideosPage from './components/Video/MyVideosPage';
import EditVideoPage from './components/Video/EditVideoPage';
import WatchPage from './components/Video/WatchPage';
import ChannelGrid from './components/Channel/ChannelGrid';
import ChannelPage from './components/Channel/ChannelPage';
import MembershipsPage from './components/Membership/MembershipsPage';
import CheckoutCompletePage from './components/Membership/CheckoutCompletePage';
import './App.css';

// Development-only showcase page. import.meta.env.DEV is false in production builds, so Vite drops
// this import and the page is not shipped.
const Demo = import.meta.env.DEV ? lazy(() => import('./pages/Demo')) : null;

function readRoute() {
  if (window.location.pathname === '/liked-videos' || window.location.hash === '#liked-videos') return { page: 'liked-videos' };
  if (window.location.pathname === '/history' || window.location.hash === '#history') return { page: 'history' };
  const watchMatch = window.location.pathname.match(/^\/watch\/(\d+)$/);
  if (watchMatch) return { page: 'watch', videoId: Number(watchMatch[1]), video: window.history.state?.video };
  const channelMatch = window.location.pathname.match(/^\/channel\/(\d+)$/);
  if (channelMatch) return { page: 'channel', channelId: Number(channelMatch[1]) };
  if (window.location.pathname === '/my-videos') return { page: 'my-videos' };
  const editMatch = window.location.pathname.match(/^\/my-videos\/(\d+)$/);
  if (editMatch) return { page: 'edit-video', videoId: Number(editMatch[1]) };
  if (window.location.pathname === '/upload') return { page: 'upload', videoId: null };
  if (window.location.pathname === '/memberships') return { page: 'memberships' };
  if (window.location.pathname === '/memberships/checkout-complete') return { page: 'checkout-complete' };
  if (Demo && window.location.pathname === '/demo') return { page: 'demo' };
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

  // Restore an existing server session after a page reload so signed-in-only UI (like My Videos) shows.
  useEffect(() => {
    let cancelled = false;
    fetch('/api/session/current', { credentials: 'include' })
      .then(response => (response.ok ? response.json() : null))
      .then(current => { if (!cancelled && current) setActiveSession(existing => existing ?? current); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (['upload', 'history', 'liked-videos', 'my-videos', 'edit-video'].includes(route.page)) return;
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
    window.addEventListener('hashchange', syncPageFromLocation);
    return () => {
      window.removeEventListener('popstate', syncPageFromLocation);
      window.removeEventListener('hashchange', syncPageFromLocation);
    };
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

  function openVideo(videoId, video) {
    window.history.pushState({ video }, '', `/watch/${videoId}`);
    setRoute({ page: 'watch', videoId, video });
  }

  function openLikedVideos() {
    window.history.pushState({}, '', '/liked-videos');
    setRoute({ page: 'liked-videos' });
  }

  function openHistory() {
    window.history.pushState({}, '', '/history');
    setRoute({ page: 'history' });
  }

  function openMyVideos() {
    window.history.pushState({}, '', '/my-videos');
    setRoute({ page: 'my-videos' });
  }

  function openEditVideo(videoId) {
    window.history.pushState({}, '', `/my-videos/${videoId}`);
    setRoute({ page: 'edit-video', videoId });
  }

  function openMemberships() {
    window.history.pushState({}, '', '/memberships');
    setRoute({ page: 'memberships' });
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
      ) : route.page === 'edit-video' ? (
        <main className="upload-main"><EditVideoPage key={route.videoId} videoId={route.videoId} onBack={openMyVideos} onDeleted={openMyVideos} /></main>
      ) : route.page === 'watch' ? (
        <WatchPage videoId={route.videoId} videos={route.video ? [route.video, ...videos.filter(video => video.videoId !== route.videoId)] : videos} videosLoading={route.video ? false : videosLoading} videosError={route.video ? '' : videosError} session={activeSession} onSelectVideo={openVideo} onSelectChannel={openChannel} onHome={returnHome} onOpenMemberships={openMemberships} />
      ) : route.page === 'memberships' ? (
        <MembershipsPage onHome={returnHome} onOpenChannel={openChannel} />
      ) : route.page === 'demo' ? (
        <Suspense fallback={<main className="demo-page"><p>Loading…</p></main>}><Demo onHome={returnHome} /></Suspense>
      ) : route.page === 'checkout-complete' ? (
        <CheckoutCompletePage onOpenChannel={openChannel} onOpenMemberships={openMemberships} />
      ) : route.page === 'channel' ? (
        <ChannelPage channelId={route.channelId} onHome={returnHome} onSelectVideo={openVideo} onSelectChannel={openChannel} onOpenMemberships={openMemberships} />
      ) : (
        <><Sidebar onOpenLikedVideos={openLikedVideos} activePage={route.page === 'liked-videos' ? 'Liked videos' : route.page === 'history' ? 'History' : route.page === 'my-videos' ? 'My Videos' : 'Home'} onOpenHistory={openHistory} onOpenMyVideos={openMyVideos} onHome={returnHome} session={activeSession} onSelectChannel={openChannel} onOpenMemberships={openMemberships} /><main className="main-content"><nav className="mobile-library-nav" aria-label="Mobile library"><a href="/" onClick={event => { event.preventDefault(); returnHome(); }} aria-current={route.page === 'home' ? 'page' : undefined}>Home</a><a href="/history" onClick={event => { event.preventDefault(); openHistory(); }} aria-current={route.page === 'history' ? 'page' : undefined}>History</a><a href="/liked-videos" onClick={event => { event.preventDefault(); openLikedVideos(); }} aria-current={route.page === 'liked-videos' ? 'page' : undefined}>Liked videos</a>{activeSession && <a href="/my-videos" onClick={event => { event.preventDefault(); openMyVideos(); }} aria-current={route.page === 'my-videos' ? 'page' : undefined}>My Videos</a>}</nav>{route.page === 'liked-videos' ? <LikedVideos key={activeSession?.userId || 'current'} onHome={returnHome} onSelectVideo={openVideo} onSelectChannel={openChannel} /> : route.page === 'history' ? <History key={activeSession?.userId || 'current'} onHome={returnHome} onSelectVideo={openVideo} /> : route.page === 'my-videos' ? <MyVideosPage onEditVideo={openEditVideo} /> : <div className="feed-content">{searchMode === 'channels' ? <ChannelGrid channels={channelResults} loading={channelsLoading} error={channelsError} query={search} onSelectChannel={openChannel} /> : <VideoGrid videos={visibleVideos} loading={videosLoading} error={videosError} onSelectVideo={openVideo} onSelectChannel={openChannel} />}</div>}</main></>
      )}
    </div>
  );
}

export default App;
