import { useState } from 'react';
import Icon from './Icon';

export default function Header({ onSearch, onCreatePage, onSessionStarted, onSelectChannel }) {
  const [query, setQuery] = useState('');
  const [searchType, setSearchType] = useState('videos');
  const [session, setSession] = useState(null);
  const [sessionError, setSessionError] = useState('');
  const [startingSession, setStartingSession] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [channels, setChannels] = useState([]);
  const [loadingChannels, setLoadingChannels] = useState(false);
  const [channelsError, setChannelsError] = useState('');
  const [openingCreate, setOpeningCreate] = useState(false);
  const [createError, setCreateError] = useState('');
  const [creatingRandomUser, setCreatingRandomUser] = useState(false);
  const [randomUserFeedback, setRandomUserFeedback] = useState('');
  const [randomUserError, setRandomUserError] = useState('');
  function submit(event) {
    event.preventDefault();
    onSearch(query, searchType);
  }

  async function createRandomUser() {
    setCreatingRandomUser(true);
    setRandomUserFeedback('');
    setRandomUserError('');
    try {
      const response = await fetch('/api/session/dev/random', {
        method: 'POST',
        credentials: 'include',
      });
      if (!response.ok) throw new Error('Could not create a user. Is the backend and database running?');
      const newSession = await response.json();
      setSession(newSession);
      onSessionStarted(newSession);
      setRandomUserFeedback(`Created ${newSession.channelName} for ${newSession.email}`);
    } catch (error) {
      setRandomUserError(error.message || 'Could not create a random user and channel.');
    } finally {
      setCreatingRandomUser(false);
    }
  }
// REMOVE LATER : UNTIL LOGIN IS COMPLETE
  async function startDevSession(event) {
    event.preventDefault();
    setStartingSession(true);
    setSessionError('');
    try {
      const response = await fetch('/api/session/dev', {
        method: 'POST',
        credentials: 'include',
      });
      if (!response.ok) throw new Error(`Session request failed (${response.status})`);
      const newSession = await response.json();
      setSession(newSession);
      onSessionStarted(newSession);
    } catch (error) {
      setSessionError('Could not start the dev session. Is the backend and database running?');
    } finally {
      setStartingSession(false);
    }
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
        setChannelsError('Click the ITube logo to start a dev session.');
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
        setCreateError('Click the ITube logo first to start a session.');
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
        <button className="icon-button menu-button" aria-label="Menu"><Icon name="menu" /></button>
        <a className="brand" href="#home" aria-label="ITube home" onClick={startDevSession} title={session ? `Signed in as ${session.email}` : 'Start dev session'}><span className="brand-mark"><Icon name="play" size={19} /></span><strong>ITube</strong></a>
      </div>
      <div className="search-area">
        <form className="search-form" onSubmit={submit}>
          <select value={searchType} onChange={event => setSearchType(event.target.value)} aria-label="Search for">
            <option value="videos">Videos</option>
            <option value="channels">Channels</option>
          </select>
          <input value={query} onChange={e => setQuery(e.target.value)} placeholder={`Search ${searchType}`} aria-label="Search" />
          <button type="submit" aria-label={`Search ${searchType}`}><Icon name="search" /></button>
        </form>
        <button className="icon-button random-user-button" type="button" onClick={createRandomUser} disabled={creatingRandomUser} title="Create a random user and channel">
          <Icon name="userPlus" size={20} /><span>{creatingRandomUser ? 'Creating…' : 'Random user'}</span>
        </button>
        {(randomUserFeedback || randomUserError) && <div className={`random-user-feedback${randomUserError ? ' is-error' : ''}`} role={randomUserError ? 'alert' : 'status'}>{randomUserError || randomUserFeedback}</div>}
      </div>
      <div className="header-actions">
        <button className="icon-button create-button" onClick={openCreatePage} disabled={openingCreate}>
          <Icon name="create" size={21} /><span>{openingCreate ? 'Checking…' : 'Create'}</span>
        </button>
        {createError && <div className="create-error" role="alert">{createError}</div>}
        <button className="icon-button notification-button" aria-label="Notifications"><Icon name="bell" /><i /></button>
        <button
          className="profile-avatar"
          aria-label={session ? `Signed in as ${session.email}` : 'Your account'}
          aria-expanded={profileOpen}
          aria-haspopup="menu"
          title={session ? `${session.email} · ${session.channelName}` : sessionError || (startingSession ? 'Starting dev session…' : 'Your account')}
          onClick={toggleProfileMenu}
        >{startingSession ? '…' : session ? 'D' : 'J'}</button>
        {profileOpen && (
          <div className="profile-menu" role="menu" aria-label="Your channels">
            <div className="profile-menu-header">
              <span className="profile-avatar menu-avatar">{session ? 'D' : 'J'}</span>
              <div><strong>{session ? 'Demo User' : 'Your account'}</strong><span>{session?.email || 'ITube dev account'}</span></div>
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
          </div>
        )}
      </div>
    </header>
  );
}
