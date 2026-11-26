import { Suspense, lazy, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { setEngagementAccount } from './api/engagement';
import Header from './components/Header';
import History from './pages/History';
import LikedVideos from './pages/LikedVideos';
import SavedVideos from './pages/SavedVideos';
import Playlists from './pages/Playlists';
import Sidebar from './components/Sidebar';
import VideoGrid from './components/Video/VideoGrid';
import UploadVideoPage from './components/Video/UploadVideoPage';
import MyVideosPage from './components/Video/MyVideosPage';
import EditVideoPage from './components/Video/EditVideoPage';
import WatchPage from './components/Video/WatchPage';
import ChannelPage from './components/Channel/ChannelPage';
import SearchResults from './components/Search/SearchResults';
import MembershipsPage from './components/Membership/MembershipsPage';
import CheckoutCompletePage from './components/Membership/CheckoutCompletePage';
import Auth from './pages/Auth';
import { fetchCurrentUser, logout } from './api/auth';
import './App.css';
import './ITube.css';

// Development-only showcase page. import.meta.env.DEV is false in production builds, so Vite drops
// this import and the page is not shipped.
const Demo = import.meta.env.DEV ? lazy(() => import('./pages/Demo')) : null;

function readRoute() {
  const automaticMatch = window.location.pathname.match(/^\/playlists\/automatic\/([a-fA-F0-9-]+)$/);
  if (automaticMatch) return { page: 'playlists', playlistId: `automatic:${automaticMatch[1]}` };
  const playlistMatch = window.location.pathname.match(/^\/playlists(?:\/([a-fA-F0-9-]+))?$/);
  if (playlistMatch) return { page: 'playlists', playlistId: playlistMatch[1] };
  if (window.location.hash === '#playlists') return { page: 'playlists' };
  if (['/saved-videos', '/watch-later'].includes(window.location.pathname) || ['#saved-videos', '#watch-later'].includes(window.location.hash)) return { page: 'saved-videos' };
  if (window.location.pathname === '/liked-videos' || window.location.hash === '#liked-videos') return { page: 'liked-videos' };
  if (window.location.pathname === '/history' || window.location.hash === '#history') return { page: 'history' };
  const watchMatch = window.location.pathname.match(/^\/watch\/(\d+)$/);
  if (watchMatch) return { page: 'watch', videoId: Number(watchMatch[1]), video: window.history.state?.video, playlistId: new URLSearchParams(window.location.search).get('playlist') || undefined };
  const channelMatch = window.location.pathname.match(/^\/channel\/(\d+)$/);
  if (channelMatch) return { page: 'channel', channelId: Number(channelMatch[1]) };
  if (window.location.pathname === '/my-videos') return { page: 'my-videos' };
  const editMatch = window.location.pathname.match(/^\/my-videos\/(\d+)$/);
  if (editMatch) return { page: 'edit-video', videoId: Number(editMatch[1]) };
  if (window.location.pathname === '/upload') return { page: 'upload', videoId: null };
  if (window.location.pathname === '/memberships') return { page: 'memberships' };
  if (window.location.pathname === '/memberships/checkout-complete') return { page: 'checkout-complete' };
  if (window.location.pathname === '/login') return { page: 'auth', mode: 'login' };
  if (window.location.pathname === '/register') return { page: 'auth', mode: 'register' };
  if (Demo && window.location.pathname === '/demo') return { page: 'demo' };
  return { page: 'home', videoId: null };
}

function App() {
  const [route, setRoute] = useState(readRoute);
  const [uploadSession, setUploadSession] = useState(null);
  const [activeSession, setActiveSession] = useState(null);
  // False until the reload check below answers, so pages don't flash a signed-out state.
  const [sessionChecked, setSessionChecked] = useState(false);
  useLayoutEffect(() => { setEngagementAccount(activeSession?.userId); }, [activeSession?.userId]);
  const [search, setSearch] = useState('');
  const [videos, setVideos] = useState([]);
  const [videosLoading, setVideosLoading] = useState(true);
  const [videosError, setVideosError] = useState('');
  const [searchFilter, setSearchFilter] = useState('all');
  const [channelResults, setChannelResults] = useState([]);
  const [channelsLoading, setChannelsLoading] = useState(false);
  const [channelsError, setChannelsError] = useState('');
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const searchRequest = useRef(0);
  const hasDockedSidebar = ['home', 'history', 'liked-videos', 'saved-videos', 'playlists', 'my-videos'].includes(route.page);

  // The drawer is a temporary overlay: close it whenever the user navigates somewhere.
  useEffect(() => { setDrawerOpen(false); }, [route]);

  useEffect(() => {
    if (!drawerOpen) return undefined;
    function closeOnEscape(event) { if (event.key === 'Escape') setDrawerOpen(false); }
    window.addEventListener('keydown', closeOnEscape);
    return () => window.removeEventListener('keydown', closeOnEscape);
  }, [drawerOpen]);

  // Pages with a docked sidebar collapse/expand it; every other page (and small screens, where the
  // docked sidebar is hidden) slides the sidebar in as an overlay drawer instead.
  function toggleMenu() {
    if (hasDockedSidebar && !window.matchMedia('(max-width: 780px)').matches) {
      setSidebarCollapsed(collapsed => !collapsed);
    } else {
      setDrawerOpen(open => !open);
    }
  }

  // Restore an existing server session after a page reload so signed-in-only UI (like My Videos) shows.
  useEffect(() => {
    let cancelled = false;
    fetchCurrentUser()
      .then(current => { if (!cancelled && current) setActiveSession(existing => existing ?? current); })
      .catch(() => {})
      .finally(() => { if (!cancelled) setSessionChecked(true); });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (['auth', 'upload', 'history', 'liked-videos', 'saved-videos', 'playlists', 'my-videos', 'edit-video'].includes(route.page)) return;
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

  async function handleSearch(query) {
    const trimmed = query.trim();
    const requestId = ++searchRequest.current;
    setSearch(trimmed);
    setSearchFilter('all');
    setChannelsError('');
    setChannelResults([]);
    if (route.page !== 'home') {
      window.history.pushState({}, '', '/');
      setRoute({ page: 'home', videoId: null });
    }
    // Videos are filtered client-side from the feed; channels come from the search endpoint.
    if (trimmed) {
      setChannelsLoading(true);
      try {
        const params = new URLSearchParams({ q: trimmed });
        const response = await fetch(`/api/channels/search?${params}`);
        if (!response.ok) throw new Error('Could not search channels.');
        const results = await response.json();
        if (requestId === searchRequest.current) setChannelResults(results);
      } catch (error) {
        if (requestId === searchRequest.current) setChannelsError(error.message || 'Could not search channels.');
      } finally {
        if (requestId === searchRequest.current) setChannelsLoading(false);
      }
    } else {
      setChannelsLoading(false);
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
    setSearch('');
    setSearchFilter('all');
    window.history.pushState({}, '', '/');
    setRoute({ page: 'home', videoId: null });
  }

  function openVideo(videoId, video, playlistId) {
    const query = playlistId ? `?${new URLSearchParams({ playlist: playlistId })}` : '';
    window.history.pushState({ video }, '', `/watch/${videoId}${query}`);
    setRoute({ page: 'watch', videoId, video, playlistId });
  }

  function openLikedVideos() {
    window.history.pushState({}, '', '/liked-videos');
    setRoute({ page: 'liked-videos' });
  }

  function openPlaylists(playlistId) {
    const path = playlistId?.startsWith('automatic:') ? `/playlists/automatic/${playlistId.slice('automatic:'.length)}` : playlistId ? `/playlists/${playlistId}` : '/playlists';
    window.history.pushState({}, '', path);
    setRoute({ page: 'playlists', playlistId });
  }

  function openSavedVideos() {
    window.history.pushState({}, '', '/saved-videos');
    setRoute({ page: 'saved-videos' });
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

  function openAuth(mode) {
    window.history.pushState({}, '', mode === 'register' ? '/register' : '/login');
    setRoute({ page: 'auth', mode });
  }

  function handleSignedIn(user) {
    setActiveSession(user);
    returnHome();
  }

  async function handleSignOut() {
    try {
      await logout();
    } finally {
      setActiveSession(null);
      returnHome();
    }
  }

  function openChannel(channelId) {
    window.history.pushState({}, '', `/channel/${channelId}`);
    setRoute({ page: 'channel', channelId });
  }

  const openLogin = () => openAuth('login');
  // Library pages and watch-page actions need an account. null means the reload check is still running.
  const signedIn = activeSession ? true : sessionChecked ? false : null;

  const sidebarProps = {
    onOpenPlaylists: openPlaylists,
    onOpenSavedVideos: openSavedVideos,
    onOpenLikedVideos: openLikedVideos,
    activePage: route.page === 'playlists' ? 'Playlists' : route.page === 'saved-videos' ? 'Watch later' : route.page === 'liked-videos' ? 'Liked videos' : route.page === 'history' ? 'History' : route.page === 'my-videos' ? 'My Videos' : route.page === 'home' ? 'Home' : '',
    onOpenHistory: openHistory,
    onOpenMyVideos: openMyVideos,
    onHome: returnHome,
    session: activeSession,
    onSelectChannel: openChannel,
    onOpenMemberships: openMemberships,
  };

  return (
    <div className={sidebarCollapsed ? 'app-shell sidebar-collapsed' : 'app-shell'} id="home">
      {route.page !== 'auth' && <Header session={activeSession} onHome={returnHome} onSearch={handleSearch} onCreatePage={openUploadPage} onSelectChannel={openChannel} onOpenAuth={openAuth} onSignOut={handleSignOut} onToggleMenu={toggleMenu} menuOpen={drawerOpen || (hasDockedSidebar && !sidebarCollapsed)} />}
      {route.page !== 'auth' && drawerOpen && <><div className="sidebar-backdrop" onClick={() => setDrawerOpen(false)} /><Sidebar {...sidebarProps} variant="drawer" onCloseDrawer={() => setDrawerOpen(false)} /></>}
      {route.page === 'auth' ? (
        <Auth key={route.mode} mode={route.mode} onSignedIn={handleSignedIn} onSwitchMode={openAuth} onHome={returnHome} />
      ) : route.page === 'upload' ? (
        <main className="upload-main"><UploadVideoPage session={uploadSession} onCancel={returnHome} /></main>
      ) : route.page === 'edit-video' ? (
        <main className="upload-main"><EditVideoPage key={route.videoId} videoId={route.videoId} onBack={openMyVideos} onDeleted={openMyVideos} /></main>
      ) : route.page === 'watch' ? (
        <WatchPage key={activeSession?.userId || 'current'} playlistId={route.playlistId} onOpenPlaylist={openPlaylists} videoId={route.videoId} videos={route.video ? [route.video, ...videos.filter(video => video.videoId !== route.videoId)] : videos} videosLoading={route.video ? false : videosLoading} videosError={route.video ? '' : videosError} session={activeSession} onSelectVideo={openVideo} onSelectChannel={openChannel} onOpenMemberships={openMemberships} onSignIn={openLogin} />
      ) : route.page === 'memberships' ? (
        <MembershipsPage onOpenChannel={openChannel} />
      ) : route.page === 'demo' ? (
        <Suspense fallback={<main className="demo-page"><p>Loading…</p></main>}><Demo /></Suspense>
      ) : route.page === 'checkout-complete' ? (
        <CheckoutCompletePage onOpenChannel={openChannel} onOpenMemberships={openMemberships} />
      ) : route.page === 'channel' ? (
        <ChannelPage channelId={route.channelId} onSelectVideo={openVideo} onSelectChannel={openChannel} onOpenMemberships={openMemberships} />
      ) : (
        <><Sidebar {...sidebarProps} /><main className="main-content"><nav className="mobile-library-nav" aria-label="Mobile library"><a href="/" onClick={event => { event.preventDefault(); returnHome(); }} aria-current={route.page === 'home' ? 'page' : undefined}>Home</a><a href="/history" onClick={event => { event.preventDefault(); openHistory(); }} aria-current={route.page === 'history' ? 'page' : undefined}>History</a><a href="/liked-videos" onClick={event => { event.preventDefault(); openLikedVideos(); }} aria-current={route.page === 'liked-videos' ? 'page' : undefined}>Liked videos</a><a href="/playlists" onClick={event => { event.preventDefault(); openPlaylists(); }} aria-current={route.page === 'playlists' ? 'page' : undefined}>Playlists</a><a href="/saved-videos" onClick={event => { event.preventDefault(); openSavedVideos(); }} aria-current={route.page === 'saved-videos' ? 'page' : undefined}>Watch later</a>{activeSession && <a href="/my-videos" onClick={event => { event.preventDefault(); openMyVideos(); }} aria-current={route.page === 'my-videos' ? 'page' : undefined}>My Videos</a>}<a href="/memberships" onClick={event => { event.preventDefault(); openMemberships(); }}>Memberships</a></nav>{signedIn === null && ['playlists', 'saved-videos', 'liked-videos', 'history'].includes(route.page) ? null : route.page === 'playlists' ? <Playlists key={`${activeSession?.userId || 'current'}:${route.playlistId || 'library'}`} playlistId={route.playlistId} onOpenPlaylist={openPlaylists} onHome={returnHome} onSelectVideo={openVideo} onSelectChannel={openChannel} onSignIn={openLogin} signedIn={signedIn} /> : route.page === 'saved-videos' ? <SavedVideos key={activeSession?.userId || 'current'} onHome={returnHome} onSelectVideo={openVideo} onSelectChannel={openChannel} onSignIn={openLogin} signedIn={signedIn} /> : route.page === 'liked-videos' ? <LikedVideos key={activeSession?.userId || 'current'} onHome={returnHome} onSelectVideo={openVideo} onSelectChannel={openChannel} onSignIn={openLogin} signedIn={signedIn} /> : route.page === 'history' ? <History key={activeSession?.userId || 'current'} onHome={returnHome} onSelectVideo={openVideo} onSignIn={openLogin} signedIn={signedIn} /> : route.page === 'my-videos' ? <MyVideosPage onEditVideo={openEditVideo} /> : <div className="feed-content">{search ? <SearchResults query={search} filter={searchFilter} onFilterChange={setSearchFilter} videos={visibleVideos} videosLoading={videosLoading} videosError={videosError} channels={channelResults} channelsLoading={channelsLoading} channelsError={channelsError} onSelectVideo={openVideo} onSelectChannel={openChannel} /> : <><h1 className="feed-title">Discover</h1><VideoGrid videos={visibleVideos} loading={videosLoading} error={videosError} onSelectVideo={openVideo} onSelectChannel={openChannel} showFeatured /></>}</div>}</main></>
      )}
    </div>
  );
}

export default App;
