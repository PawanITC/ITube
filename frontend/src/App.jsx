import { useEffect, useMemo, useState } from 'react';
import History from './pages/History';
import Header from './components/Header';
import Sidebar from './components/Sidebar';
import CategoryBar from './components/CategoryBar';
import VideoGrid from './components/VideoGrid';
import videos from './data/videos';
import './App.css';

function App() {
  const [route, setRoute] = useState(() => window.location.hash || '#home');
  useEffect(() => {
    const navigate = () => setRoute(window.location.hash || '#home');
    window.addEventListener('hashchange', navigate);
    return () => window.removeEventListener('hashchange', navigate);
  }, []);
  const isHistory = route === '#history';
  const [category, setCategory] = useState('All');
  const [search, setSearch] = useState('');
  const visibleVideos = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return videos.filter(video => !needle || `${video.title} ${video.channel}`.toLowerCase().includes(needle));
  }, [search]);

  return <div className="app-shell"><Header onSearch={value => { setSearch(value); window.location.hash = 'home'; }} /><Sidebar activePage={isHistory ? 'History' : 'Home'} /><main className="main-content"><nav className="mobile-library-nav" aria-label="Mobile library"><a href="#home" aria-current={!isHistory ? 'page' : undefined}>Home</a><a href="#history" aria-current={isHistory ? 'page' : undefined}>History</a></nav>{isHistory ? <History /> : <><CategoryBar selected={category} onSelect={setCategory} /><div className="feed-content"><VideoGrid videos={visibleVideos} /></div></>}</main></div>;
}

export default App;
