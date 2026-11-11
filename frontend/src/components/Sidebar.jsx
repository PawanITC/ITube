import { useEffect, useState } from 'react';
import Icon from './Icon';

const primary = [['home', 'Home'], ['shorts', 'Shorts'], ['subscriptions', 'Subscriptions']];
const library = [['history', 'History'], ['playlist', 'Playlists'], ['later', 'Watch later'], ['liked', 'Liked videos']];

function NavLink({ icon, children, active = false, onNavigate }) {
  return <a href={`#${children.toLowerCase().replaceAll(' ', '-')}`} onClick={onNavigate ? event => { event.preventDefault(); onNavigate(); } : undefined} aria-current={active ? 'page' : undefined} aria-label={children} className={`nav-link${active ? ' active' : ''}`}><Icon name={icon} size={22} /><span>{children}</span></a>;
}

export default function Sidebar({ session, onSelectChannel, onOpenMemberships, activePage = 'Home', onOpenHistory, onOpenMyVideos, onHome, onOpenLikedVideos }) {
  const [subscriptions, setSubscriptions] = useState([]);
  const [loadingSubscriptions, setLoadingSubscriptions] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoadingSubscriptions(true);
    fetch('/api/channels/subscriptions', { credentials: 'include' })
      .then(response => response.ok ? response.json() : [])
      .then(items => { if (!cancelled) setSubscriptions(items); })
      .catch(() => { if (!cancelled) setSubscriptions([]); })
      .finally(() => { if (!cancelled) setLoadingSubscriptions(false); });
    return () => { cancelled = true; };
  }, [session?.userId]);

  return (
    <aside className="sidebar">
      <nav>
        <div className="nav-group">{primary.map(([icon, label]) => <NavLink key={label} icon={icon} active={label === activePage} onNavigate={label === 'Home' ? onHome : undefined}>{label}</NavLink>)}</div>
        <div className="nav-group library-group"><div className="nav-heading">You <span>›</span></div>{session && <a href="/my-videos" className={`nav-link${activePage === 'My Videos' ? ' active' : ''}`} aria-current={activePage === 'My Videos' ? 'page' : undefined} aria-label="My Videos" onClick={event => { event.preventDefault(); onOpenMyVideos?.(); }}><Icon name="myVideos" size={22} /><span>My Videos</span></a>}{library.map(([icon, label]) => <NavLink key={label} icon={icon} active={label === activePage} onNavigate={label === 'History' ? onOpenHistory : label === 'Liked videos' ? onOpenLikedVideos : undefined}>{label}</NavLink>)}{import.meta.env.DEV && <a href="/demo" className="nav-link" title="Development only"><Icon name="play" size={22} /><span>Feature showcase</span></a>}<a href="/memberships" className="nav-link" onClick={event => { event.preventDefault(); onOpenMemberships?.(); }}><Icon name="liked" size={22} /><span>Memberships</span></a></div>
        <div className="nav-group subscriptions-group">
          <h3>Subscriptions</h3>
          {loadingSubscriptions && <p className="sidebar-subscription-message">Loading…</p>}
          {!loadingSubscriptions && subscriptions.length === 0 && <p className="sidebar-subscription-message">No subscriptions yet</p>}
          {!loadingSubscriptions && subscriptions.map(channel => (
            <a href={`/channel/${channel.channelId}`} className="channel-link" key={channel.channelId} onClick={event => { event.preventDefault(); onSelectChannel(channel.channelId); }}>
              <span className="mini-avatar">{channel.channelName.trim().slice(0, 1).toUpperCase() || '?'}</span>
              <span>{channel.channelName}</span>
            </a>
          ))}
        </div>
      </nav>
      <footer>About&nbsp; Press&nbsp; Copyright<br />Contact us&nbsp; Creators<br /><br />Terms&nbsp; Privacy&nbsp; Policy &amp; Safety<br />© 2026 Google LLC</footer>
    </aside>
  );
}
