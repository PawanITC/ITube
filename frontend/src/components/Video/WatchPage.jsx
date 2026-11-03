import { useEffect, useMemo, useRef, useState } from 'react';
import Icon from '../Icon.jsx';
import VideoCard from './VideoCard.jsx';
import JoinButton from '../Membership/JoinButton.jsx';

const LOCKED_MESSAGES = {
  SIGN_IN_REQUIRED: 'This video is for channel members. Click the ITube logo to start a session, then join the channel to watch.',
  MEMBERSHIP_REQUIRED: 'This video is for channel members. Join the channel to watch it.',
  HIGHER_TIER_REQUIRED: 'This video is for members on a higher tier. Upgrade your membership to watch it.',
};

async function fetchPlayback(videoId) {
  // The server checks entitlement and returns a short-lived token for members-only videos
  const response = await fetch(`/api/videos/${videoId}/playback-authorisation`, { credentials: 'include' });
  if (!response.ok) throw new Error(response.status === 404 ? 'Video not found.' : 'Could not load this video.');
  return response.json();
}

function formatCommentDate(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

function formatPlayerTime(value) {
  if (!Number.isFinite(value) || value < 0) return '0:00';
  const totalSeconds = Math.floor(value);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return hours > 0
    ? `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
    : `${minutes}:${String(seconds).padStart(2, '0')}`;
}

export default function WatchPage({ videoId, videos, videosLoading, videosError, session, onSelectVideo, onSelectChannel, onHome, onOpenMemberships }) {
  const [playback, setPlayback] = useState(null);
  const [playbackError, setPlaybackError] = useState('');
  const [comments, setComments] = useState([]);
  const [commentsLoading, setCommentsLoading] = useState(true);
  const [commentsError, setCommentsError] = useState('');
  const [hasSession, setHasSession] = useState(Boolean(session));
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const [qualities, setQualities] = useState([]);
  const [selectedQuality, setSelectedQuality] = useState('source');
  const [qualityMenuOpen, setQualityMenuOpen] = useState(false);
  const [settingsPanel, setSettingsPanel] = useState('root');
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolume] = useState(1);
  const [isMuted, setIsMuted] = useState(false);
  const [playbackRate, setPlaybackRate] = useState(1);
  const [theaterMode, setTheaterMode] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const videoRef = useRef(null);
  const playerWrapRef = useRef(null);
  const playbackRef = useRef(null);
  const lastTokenRefreshRef = useRef(0);
  const [processingStatus, setProcessingStatus] = useState('PROCESSING');
  const video = useMemo(() => videos.find(item => item.videoId === videoId), [videos, videoId]);
  const recommendations = useMemo(() => videos.filter(item => item.videoId !== videoId), [videos, videoId]);

  useEffect(() => {
    const updateFullscreen = () => setIsFullscreen(document.fullscreenElement === playerWrapRef.current);
    document.addEventListener('fullscreenchange', updateFullscreen);
    return () => document.removeEventListener('fullscreenchange', updateFullscreen);
  }, []);

  useEffect(() => {
    let cancelled = false;
    let timer;
    setSelectedQuality('source');
    async function refreshProcessing() {
      try {
        const [videoResponse, qualityResponse] = await Promise.all([
          fetch('/api/videos'),
          fetch(`/api/videos/${videoId}/qualities`),
        ]);
        if (!videoResponse.ok || !qualityResponse.ok) return;
        const currentVideo = (await videoResponse.json()).find(item => item.videoId === videoId);
        const availableQualities = await qualityResponse.json();
        if (cancelled) return;
        if (currentVideo) setProcessingStatus(currentVideo.processingStatus);
        setQualities(availableQualities);
        if (currentVideo?.processingStatus === 'PROCESSING') timer = setTimeout(refreshProcessing, 3000);
      } catch { if (!cancelled) timer = setTimeout(refreshProcessing, 5000); }
    }
    refreshProcessing();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [videoId]);

  useEffect(() => {
    if (session) setHasSession(true);
  }, [session]);

  useEffect(() => {
    let cancelled = false;
    setPlayback(null);
    setPlaybackError('');
    fetchPlayback(videoId)
      .then(result => { if (!cancelled) setPlayback(result); })
      .catch(error => { if (!cancelled) setPlaybackError(error.message); });
    return () => { cancelled = true; };
  }, [videoId, session]);

  const tokenQuery = playback?.token ? `?token=${encodeURIComponent(playback.token)}` : '';

  useEffect(() => {
    let cancelled = false;
    setComments([]);
    setCommentsLoading(true);
    setCommentsError('');
    fetch(`/api/videos/${videoId}/comments`)
      .then(async response => {
        if (!response.ok) throw new Error(response.status === 404 ? 'Video not found.' : 'Could not load comments.');
        return response.json();
      })
      .then(result => { if (!cancelled) setComments(result); })
      .catch(error => { if (!cancelled) setCommentsError(error.message); })
      .finally(() => { if (!cancelled) setCommentsLoading(false); });

    if (!session) {
      fetch('/api/session/current', { credentials: 'include' })
        .then(response => { if (cancelled) return; setHasSession(response.ok); })
        .catch(() => { if (!cancelled) setHasSession(false); });
    } else {
      setHasSession(true);
    }
    return () => { cancelled = true; };
  }, [videoId, session]);

  async function submitComment(event) {
    event.preventDefault();
    const content = draft.trim();
    if (!content || sending) return;
    setSending(true);
    setCommentsError('');
    try {
      const response = await fetch(`/api/videos/${videoId}/comments`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content }),
      });
      if (response.status === 401) {
        setHasSession(false);
        throw new Error('Click the ITube logo to start a session before commenting.');
      }
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail || 'Could not post your comment.');
      }
      const createdComment = await response.json();
      setComments(current => [createdComment, ...current]);
      setDraft('');
    } catch (error) {
      setCommentsError(error.message || 'Could not post your comment.');
    } finally {
      setSending(false);
    }
  }

  async function selectQuality(quality) {
    // Tokens are short-lived; get a fresh one before switching source if it's about to expire
    if (playback?.expiresAt && Date.parse(playback.expiresAt) - Date.now() < 30_000) {
      try { setPlayback(await fetchPlayback(videoId)); } catch { /* keep the current token */ }
    }
    const player = videoRef.current;
    playbackRef.current = player ? { time: player.currentTime, wasPlaying: !player.paused } : null;
    setSelectedQuality(quality);
    setQualityMenuOpen(false);
    setSettingsPanel('root');
  }

  // Tokens last the video's length, but one can still expire (e.g. paused for hours). When a chunk
  // request is refused, get a fresh token and resume where the viewer was. If access has ended,
  // the new answer shows the locked screen instead.
  async function recoverFromPlaybackError() {
    if (!playback?.token || Date.now() - lastTokenRefreshRef.current < 30_000) return;
    lastTokenRefreshRef.current = Date.now();
    const player = videoRef.current;
    playbackRef.current = player ? { time: player.currentTime, wasPlaying: !player.paused } : null;
    try { setPlayback(await fetchPlayback(videoId)); } catch { /* leave the browser's error showing */ }
  }

  function restorePlayback() {
    const playback = playbackRef.current;
    const player = videoRef.current;
    if (!playback || !player) return;
    player.currentTime = playback.time;
    if (playback.wasPlaying) player.play().catch(() => {});
    playbackRef.current = null;
  }

  function togglePlayback() {
    const player = videoRef.current;
    if (!player) return;
    if (player.paused) player.play().catch(() => {});
    else player.pause();
  }

  function changeVolume(value) {
    const player = videoRef.current;
    if (!player) return;
    player.volume = Number(value);
    player.muted = Number(value) === 0;
  }

  function toggleMute() {
    const player = videoRef.current;
    if (player) player.muted = !player.muted;
  }

  function changePlaybackRate(rate) {
    const value = Number(rate);
    if (videoRef.current) videoRef.current.playbackRate = value;
    setPlaybackRate(value);
    setSettingsPanel('root');
  }

  async function toggleFullscreen() {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await playerWrapRef.current?.requestFullscreen();
  }

  async function togglePictureInPicture() {
    const player = videoRef.current;
    if (!player || !document.pictureInPictureEnabled) return;
    if (document.pictureInPictureElement) await document.exitPictureInPicture();
    else await player.requestPictureInPicture();
  }

  return (
    <main className="watch-main">
      <button type="button" className="watch-back" onClick={onHome}>‹ Back to videos</button>
      {videosLoading ? <p className="watch-message">Loading video…</p> : videosError ? <p className="watch-message" role="alert">{videosError}</p> : !video ? <p className="watch-message">Video not found.</p> : (
        <div className={`watch-layout${theaterMode ? ' is-theater' : ''}`}>
          <section className="watch-primary">
            {processingStatus === 'PROCESSING' && <p className="watch-message" role="status">Preparing quality options… The original is available now.</p>}
            {processingStatus === 'FAILED' && <p className="watch-message" role="status">Quality processing failed. The original video is still available.</p>}
            {playbackError ? <p className="watch-message" role="alert">{playbackError}</p> : !playback ? <p className="watch-message">Checking access…</p> : !playback.allowed ? (
              <div className="locked-player" role="region" aria-label="Members-only video">
                <span className="members-badge">Members only</span>
                <h2>{video.title}</h2>
                <p>{LOCKED_MESSAGES[playback.reason] || 'You do not have access to this video.'}</p>
                {playback.reason !== 'SIGN_IN_REQUIRED' && <JoinButton channelId={video.channelId} requiredLevel={playback.requiredTierLevel || undefined} onOpenMemberships={onOpenMemberships} label="Join to watch" />}
              </div>
            ) : (
            <div ref={playerWrapRef} className={`watch-player-wrap${theaterMode ? ' is-theater' : ''}`}>
              <video ref={videoRef} className="watch-player" src={selectedQuality === 'source' ? playback.sourceUrl : `/api/videos/${videoId}/renditions/${selectedQuality}${tokenQuery}`} onClick={togglePlayback} onLoadedMetadata={event => { setDuration(event.currentTarget.duration); setCurrentTime(event.currentTarget.currentTime); restorePlayback(); }} onTimeUpdate={event => setCurrentTime(event.currentTarget.currentTime)} onDurationChange={event => setDuration(event.currentTarget.duration)} onPlay={() => setIsPlaying(true)} onPause={() => setIsPlaying(false)} onVolumeChange={event => { setVolume(event.currentTarget.volume); setIsMuted(event.currentTarget.muted); }} onEnded={() => setIsPlaying(false)} onError={recoverFromPlaybackError} onRateChange={event => setPlaybackRate(event.currentTarget.playbackRate)} poster={video.thumbnailUrl || undefined} autoPlay playsInline />
              {!isPlaying && <button type="button" className="watch-center-play" aria-label="Play video" onClick={togglePlayback}><Icon name="play" size={34} /></button>}
              <div className="watch-controls">
                <input className="watch-seek" type="range" min="0" max={duration || 0} step="0.1" value={Math.min(currentTime, duration || 0)} aria-label="Seek video" style={{ '--seek-progress': `${duration ? (currentTime / duration) * 100 : 0}%` }} onChange={event => { if (videoRef.current) videoRef.current.currentTime = Number(event.target.value); }} />
                <div className="watch-controls-row">
                  <div className="watch-controls-group">
                    <button type="button" className="watch-control-button" aria-label={isPlaying ? 'Pause' : 'Play'} onClick={togglePlayback}><Icon name={isPlaying ? 'pause' : 'play'} /></button>
                    <button type="button" className="watch-control-button" aria-label={isMuted || volume === 0 ? 'Unmute' : 'Mute'} onClick={toggleMute}><Icon name={isMuted || volume === 0 ? 'mute' : 'volume'} /></button>
                    <input className="watch-volume" type="range" min="0" max="1" step="0.05" value={isMuted ? 0 : volume} aria-label="Volume" onChange={event => changeVolume(event.target.value)} />
                    <span className="watch-time">{formatPlayerTime(currentTime)} / {formatPlayerTime(duration)}</span>
                  </div>
                  <div className="watch-controls-group">
                    <div className="watch-settings">
                      <button type="button" className="watch-control-button" aria-label="Player settings" aria-haspopup="menu" aria-expanded={qualityMenuOpen} onClick={() => { setQualityMenuOpen(open => !open); setSettingsPanel('root'); }}><Icon name="settings" /></button>
                      {qualityMenuOpen && <div className="watch-settings-menu" role="menu" aria-label="Player settings">
                        {settingsPanel === 'root' ? <>
                          <div className="watch-settings-heading">Settings</div>
                          {qualities.length > 0 && <button type="button" role="menuitem" className="watch-settings-row" onClick={() => setSettingsPanel('quality')}><span>Quality</span><span>{selectedQuality === 'source' ? 'Original' : selectedQuality} ›</span></button>}
                          <button type="button" role="menuitem" className="watch-settings-row" onClick={() => setSettingsPanel('speed')}><span>Playback speed</span><span>{playbackRate === 1 ? 'Normal' : `${playbackRate}x`} ›</span></button>
                        </> : settingsPanel === 'quality' ? <>
                          <button type="button" className="watch-settings-back" onClick={() => setSettingsPanel('root')}>‹ <span>Quality</span></button>
                          {[['source', 'Original'], ...qualities.map(quality => [quality, quality])].map(([quality, label]) => <button type="button" role="menuitemradio" aria-checked={selectedQuality === quality} className="watch-settings-row" key={quality} onClick={() => selectQuality(quality)}><span>{selectedQuality === quality ? '✓' : ''}</span><span>{label}</span></button>)}
                        </> : <>
                          <button type="button" className="watch-settings-back" onClick={() => setSettingsPanel('root')}>‹ <span>Playback speed</span></button>
                          {[0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2].map(rate => <button type="button" role="menuitemradio" aria-checked={playbackRate === rate} className="watch-settings-row" key={rate} onClick={() => changePlaybackRate(rate)}><span>{playbackRate === rate ? '✓' : ''}</span><span>{rate === 1 ? 'Normal' : `${rate}x`}</span></button>)}
                        </>}
                      </div>}
                    </div>
                    <button type="button" className={`watch-control-button${theaterMode ? ' is-active' : ''}`} aria-label={theaterMode ? 'Exit theater mode' : 'Theater mode'} aria-pressed={theaterMode} onClick={() => setTheaterMode(mode => !mode)}><Icon name="theater" /></button>
                    {document.pictureInPictureEnabled && <button type="button" className="watch-control-button" aria-label="Picture in picture" onClick={togglePictureInPicture}><Icon name="pip" /></button>}
                    <button type="button" className="watch-control-button" aria-label={isFullscreen ? 'Exit fullscreen' : 'Fullscreen'} onClick={toggleFullscreen}><Icon name="fullscreen" /></button>
                  </div>
                </div>
              </div>
            </div>
            )}
            <h1 className="watch-title">{video.visibility === 'MEMBERS_ONLY' && <span className="members-badge inline">Members only</span>}{video.title}</h1>
            <div className="watch-channel-row">
              <a className="watch-channel" href={`/channel/${video.channelId}`} onClick={event => { event.preventDefault(); onSelectChannel(video.channelId); }}>{video.channelName}</a>
              <JoinButton channelId={video.channelId} onOpenMemberships={onOpenMemberships} />
            </div>

            <section className="comments-section" aria-labelledby="comments-heading">
              <h2 id="comments-heading">Comments <span>({comments.length})</span></h2>
              {hasSession ? (
                <form className="comment-form" onSubmit={submitComment}>
                  <textarea value={draft} onChange={event => setDraft(event.target.value)} maxLength={5000} placeholder="Add a comment…" aria-label="Add a comment" />
                  <div className="comment-form-actions"><span>{draft.length}/5000</span><button type="submit" disabled={sending || !draft.trim()}>{sending ? 'Posting…' : 'Comment'}</button></div>
                </form>
              ) : <p className="comment-signin">Click the ITube logo to start a dev session before commenting.</p>}
              {commentsError && <p className="comment-error" role="alert">{commentsError}</p>}
              {commentsLoading ? <p className="comment-muted">Loading comments…</p> : comments.length === 0 ? <p className="comment-muted">No comments yet. Start the conversation.</p> : (
                <ul className="comment-list">
                  {comments.map(comment => <li className="comment-item" key={comment.commentId}>
                    <span className="comment-avatar">{comment.authorName?.trim().slice(0, 1).toUpperCase() || '?'}</span>
                    <div className="comment-body"><div className="comment-meta"><strong>{comment.authorName}</strong><time dateTime={comment.createdAt}>{formatCommentDate(comment.createdAt)}</time></div><p>{comment.content}</p></div>
                  </li>)}
                </ul>
              )}
            </section>
          </section>

          <aside className="watch-recommendations" aria-label="Recommended videos">
            <h2>Up next</h2>
            {recommendations.length ? recommendations.map(recommended => (
              <button type="button" className="recommendation-card" key={recommended.videoId} onClick={() => onSelectVideo(recommended.videoId)}>
                <span className="recommendation-thumb"><img src={recommended.thumbnailUrl || '/thumbnail-placeholder.svg'} alt="" /><span className="duration">{recommended.duration}</span><span className="recommendation-play"><Icon name="play" size={20} /></span></span>
                <span className="recommendation-copy"><strong>{recommended.title}</strong><span>{recommended.channelName}</span></span>
              </button>
            )) : <p className="comment-muted">No other videos yet.</p>}
          </aside>
        </div>
      )}
    </main>
  );
}
