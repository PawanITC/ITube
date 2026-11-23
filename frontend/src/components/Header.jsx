import { useState } from 'react';
import Icon from './Icon';

function displayName(session) {
  if (!session) return '';
  const fullName = [session.firstname, session.lastname].filter(Boolean).join(' ');
  return fullName || session.channelName || session.email || 'Your account';
}

export default function Header({ session, onHome, onSearch, onCreatePage, onSelectChannel, onOpenAuth, onSignOut, onToggleMenu, menuOpen }) {
  const [query, setQuery] = useState('');
  const [profileOpen, setProfileOpen] = useState(false);
  const [channels, setChannels] = useState([]);
  const [loadingChannels, setLoadingChannels] = useState(false);
  const [channelsError, setChannelsError] = useState('');
  const [openingCreate, setOpeningCreate] = useState(false);
  const [createError, setCreateError] = useState('');
  function submit(event) {
    event.preventDefault();
    onSearch(query);
  }

  function goHome(event) {
    event.preventDefault();
    setQuery('');
    setProfileOpen(false);
    onHome();
  }

  async function toggleProfileMenu() {
    const shouldOpen = !profileOpen;
    setProfileOpen(shouldOpen);
    if (!shouldOpen) return;

    setLoadingChannels(true);
    setChannelsError('');
    try {
      const response = await fetch('/api/channels/mine', { credentials: 'include' });
      if (response.status === 401) {
        setChannelsError('Sign in to see your channels.');
        setChannels([]);
        return;
      }
      if (!response.ok) throw new Error(`Channel request failed (${response.status})`);
      setChannels(await response.json());
    } catch (error) {
      setChannelsError('Could not load your channels. Check that the backend is running.');
      setChannels([]);
    } finally {
      setLoadingChannels(false);
    }
  }

  async function openCreatePage() {
    setOpeningCreate(true);
    setCreateError('');
    try {
      const response = await fetch('/api/session/current', { credentials: 'include' });
      if (response.status === 401) {
        onOpenAuth('login');
        return;
      }
      if (!response.ok) throw new Error(`Session request failed (${response.status})`);
      onCreatePage(await response.json());
    } catch (error) {
      setCreateError('Could not check your session. Make sure the backend is running.');
    } finally {
      setOpeningCreate(false);
    }
  }

  return (
    <header className="topbar">
      <div className="brand-area">
        <button className="icon-button menu-button" type="button" aria-label="Menu" aria-expanded={menuOpen} onClick={onToggleMenu}><Icon name="menu" /></button>
        <a className="brand" href="/" aria-label="ITube home" onClick={goHome} title="ITube home"><span className="brand-mark"><Icon name="play" size={19} /></span><strong>ITube</strong></a>
      </div>
      <div className="search-area">
        <form className="search-form" onSubmit={submit}>
          <input value={query} onChange={e => setQuery(e.target.value)} placeholder="Search" aria-label="Search" />
          <button type="submit" aria-label="Search"><Icon name="search" /></button>
        </form>
      </div>
      <div className="header-actions">
        <button className="icon-button create-button" onClick={openCreatePage} disabled={openingCreate}>
          <Icon name="create" size={21} /><span>{openingCreate ? 'Checking…' : 'Create'}</span>
        </button>
        {createError && <div className="create-error" role="alert">{createError}</div>}
        <button className="icon-button notification-button" aria-label="Notifications"><Icon name="bell" /><i /></button>
        {session ? (
          <button
            className="profile-avatar"
            aria-label={`Account menu for ${displayName(session)}`}
            aria-expanded={profileOpen}
            aria-haspopup="menu"
            title={session.email}
            onClick={toggleProfileMenu}
          >{displayName(session).charAt(0).toUpperCase()}</button>
        ) : (
          <button className="sign-in-button" type="button" onClick={() => onOpenAuth('login')}>
            <Icon name="user" size={18} /><span>Sign in</span>
          </button>
        )}
        {session && profileOpen && (
          <div className="profile-menu" role="menu" aria-label="Account">
            <div className="profile-menu-header">
              <span className="profile-avatar menu-avatar">{displayName(session).charAt(0).toUpperCase()}</span>
              <div><strong>{displayName(session)}</strong><span>{session.email}</span></div>
            </div>
            <div className="profile-menu-divider" />
            <h2>Your channels</h2>
            {loadingChannels && <p className="profile-menu-message">Loading channels…</p>}
            {!loadingChannels && channelsError && <p className="profile-menu-message">{channelsError}</p>}
            {!loadingChannels && !channelsError && channels.length === 0 && <p className="profile-menu-message">You don’t have any channels yet.</p>}
            {!loadingChannels && channels.map(channel => (
              <a className="profile-channel" href={`/channel/${channel.channelId}`} key={channel.channelId} role="menuitem" onClick={event => { event.preventDefault(); setProfileOpen(false); onSelectChannel(channel.channelId); }}>
                <span className="mini-avatar">{channel.channelName.charAt(0).toUpperCase()}</span>
                <span>{channel.channelName}</span>
              </a>
            ))}
            <div className="profile-menu-divider" />
            <button type="button" className="profile-menu-action" role="menuitem" onClick={() => { setProfileOpen(false); onSignOut(); }}>
              <Icon name="signOut" size={20} /><span>Sign out</span>
            </button>
          </div>
        )}
      </div>
    </header>
  );
}
