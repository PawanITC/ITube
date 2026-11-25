import { useEffect, useMemo, useRef, useState } from 'react';
import Icon from '../Icon.jsx';
import LikeButton from '../LikedVideos/LikeButton';
import SaveButton from '../SavedVideos/SaveButton';
import AddToPlaylistButton from '../Playlists/AddToPlaylistButton';
import PlaylistQueue from '../Playlists/PlaylistQueue';
import usePlaylistQueue from '../Playlists/usePlaylistQueue';
import { recordWatch } from '../../api/history';
import VideoCard from './VideoCard.jsx';
import JoinButton from '../Membership/JoinButton.jsx';
import VideoDescription from './VideoDescription.jsx';
import {
  FIRST_PROBE_BYTES, PROBE_BYTES, PROBE_INTERVAL_MS, SAFETY_FACTOR, STALL_WINDOW_MS, STALLS_TO_DOWNGRADE,
  browserEstimateKbps, decideQuality, measureBandwidth, qualityHeight, stepDown, toLevels, updateEstimate,
} from './adaptiveQuality';

const LOCKED_MESSAGES = {
  SIGN_IN_REQUIRED: 'This video is for channel members. Click the ITube logo to start a session, then join the channel to watch.',
  MEMBERSHIP_REQUIRED: 'This video is for channel members. Join the channel to watch it.',
  HIGHER_TIER_REQUIRED: 'This video is for members on a higher tier. Upgrade your membership to watch it.',
};

/** Ads only play in videos longer than this (the server checks it too). */
const MIN_VIDEO_SECONDS_FOR_ADS = 60;
/** Ads longer than this can be skipped... */
const SKIPPABLE_AD_SECONDS = 10;
/** ...once this much of them has played. */
const SKIP_AFTER_SECONDS = 5;

/** Counts one view of a video or ad. Best-effort: a failure never interrupts playback. */
const VIEWER_ID_KEY = 'itube-viewer-id';

/**
 * A random id this browser keeps, so a signed-out viewer counts as one view per video however often they watch.
 * Signed-in viewers are recognised by their account instead. Null if the browser won't store it.
 */
export function viewerId() {
  try {
    let id = window.localStorage.getItem(VIEWER_ID_KEY);
    if (!id || !/^[A-Za-z0-9-]{8,48}$/.test(id)) {
      id = window.crypto?.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
      window.localStorage.setItem(VIEWER_ID_KEY, id);
    }
    return id;
  } catch {
    return null;
  }
}

/**
 * Tells the server this viewer started watching a video or ad. Resolves to whether it counted as a new view
 * (false when this viewer was already counted); rejects if the request failed. A failure never interrupts playback.
 */
async function sendView(videoId) {
  const id = viewerId();
  const response = await fetch(`/api/videos/${videoId}/views`, {
    method: 'POST',
    credentials: 'include',
    headers: id ? { 'X-Viewer-Id': id } : {},
  });
  if (!response.ok) throw new Error(`View not recorded (${response.status})`);
  const result = await response.json().catch(() => null);
  return Boolean(result?.counted);
}

/** "HH:MM:SS" or "MM:SS" from the video list -> seconds; 0 if unknown. */
function durationSeconds(value) {
  if (typeof value !== 'string') return 0;
  return value.split(':').map(Number).reduce((total, part) => (Number.isFinite(part) ? total * 60 + part : total), 0);
}

/** How the browser can play HLS: hls.js (Media Source Extensions), natively (Safari, iOS), or not at all. */
function hlsSupport() {
  // What hls.js needs (its own isSupported check), without loading hls.js just to ask
  const MediaSourceType = typeof window !== 'undefined' ? window.ManagedMediaSource || window.MediaSource : undefined;
  if (MediaSourceType?.isTypeSupported?.('video/mp4; codecs="avc1.42E01E,mp4a.40.2"')) return 'hlsjs';
  if (typeof document !== 'undefined' && document.createElement('video').canPlayType('application/vnd.apple.mpegurl')) return 'native';
  return 'none';
}

function hlsLevelName(level) {
  if (!level) return '';
  if (level.name) return level.name;
  return level.height ? `${level.height}p` : `${Math.round((level.bitrate || 0) / 1000)} kbps`;
}

/** hls.js gives up on a broken stream after this many recoveries and the uploaded file plays instead. */
const MAX_HLS_RECOVERIES = 3;

/** Ignore the buffering a quality switch causes by itself. */
const SWITCH_SETTLE_MS = 4000;
/** Longest the player waits for the first bandwidth measurement before starting anyway. */
const FIRST_CHOICE_TIMEOUT_MS = 2500;

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

export default function WatchPage({ videoId, videos, videosLoading, videosError, session, onSelectVideo, onSelectChannel, onOpenMemberships, playlistId, onOpenPlaylist }) {
  const playlistQueue = usePlaylistQueue(playlistId, videoId);
  const [playback, setPlayback] = useState(null);
  const [playbackError, setPlaybackError] = useState('');
  const [comments, setComments] = useState([]);
  const [commentsLoading, setCommentsLoading] = useState(true);
  const [commentsError, setCommentsError] = useState('');
  const [hasSession, setHasSession] = useState(Boolean(session));
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  // The quality actually playing: null until chosen, 'source' only while no transcoded quality exists yet
  const [selectedQuality, setSelectedQuality] = useState(null);
  // 'auto' (chosen from the connection) or the quality the viewer picked
  const [qualityMode, setQualityMode] = useState('auto');
  const [qualityLevels, setQualityLevels] = useState([]);
  const [qualitiesLoaded, setQualitiesLoaded] = useState(false);
  const bandwidthRef = useRef(null);
  const lastSwitchRef = useRef(0);
  const lastStallRef = useRef(0);
  const stallTimesRef = useRef([]);
  const autoRef = useRef({});
  // HLS (adaptive streaming): hls.js picks the quality segment by segment
  const hlsRef = useRef(null);
  const [hlsQualities, setHlsQualities] = useState([]);
  const [hlsPlayingQuality, setHlsPlayingQuality] = useState('');
  const [hlsFailed, setHlsFailed] = useState(false);
  const recoverRef = useRef(null);
  const lastQualityReloadRef = useRef(0);
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
  const historyAttempt = useRef(null);
  const [details, setDetails] = useState(null);
  const [detailsLoading, setDetailsLoading] = useState(true);
  const [detailsError, setDetailsError] = useState('');
  // One view per visit to a video, and one per ad shown
  const viewRecordedRef = useRef(null);
  const countedAdRef = useRef(null);
  // Ads: the server says where ads go (by default one at the midpoint). Each plays once per visit: when playback
  // reaches it, by watching or by seeking past it. Played ones lose their marker on the seek bar.
  const [adBreaks, setAdBreaks] = useState([]);
  const [playedBreaks, setPlayedBreaks] = useState(() => new Set());
  const [activeAd, setActiveAd] = useState(null);
  const [adRemaining, setAdRemaining] = useState(0);
  const [adElapsed, setAdElapsed] = useState(0);
  const [adPlaying, setAdPlaying] = useState(false);
  const [adMuted, setAdMuted] = useState(false);
  const playedBreaksRef = useRef(new Set());
  const adRef = useRef(null);
  const [processingStatus, setProcessingStatus] = useState('PROCESSING');
  // A playlist may include unlisted videos absent from the public feed, including after a page reload.
  const video = useMemo(() => videos.find(item => item.videoId === videoId)
    || playlistQueue.videos.find(item => item.videoId === videoId)?.video, [videos, videoId, playlistQueue.videos]);
  const recommendations = useMemo(() => videos.filter(item => item.videoId !== videoId), [videos, videoId]);

  useEffect(() => {
    // A new video visit or account starts a new viewing session. Quality changes do not.
    historyAttempt.current = null;
  }, [videoId, session?.userId]);

  function recordPlaybackHistory() {
    // "playing" fires when playback really starts, including autoplay; not on loading the page.
    // Guard against repeated resume/buffering events and stale authorisation from the previous video.
    if (!playback?.allowed || playback.videoId !== videoId || historyAttempt.current) return;
    const attempt = {};
    historyAttempt.current = attempt;
    recordWatch(videoId).catch(error => {
      // History is best-effort: never interrupt playback. A later playing event may retry a failure.
      if (historyAttempt.current === attempt) historyAttempt.current = null;
      if (error.status !== 401) console.warn('Unable to save watch history.', error);
    });
  }

  useEffect(() => { viewRecordedRef.current = null; }, [videoId]);

  useEffect(() => {
    let cancelled = false;
    setDetails(current => (current?.videoId === videoId ? current : null));
    setDetailsLoading(true);
    setDetailsError('');
    fetch(`/api/videos/${videoId}/details`, { credentials: 'include' })
      .then(response => {
        if (!response.ok) throw new Error('Could not load the description.');
        return response.json();
      })
      .then(result => { if (!cancelled) setDetails(result); })
      .catch(error => { if (!cancelled) setDetailsError(error.message); })
      .finally(() => { if (!cancelled) setDetailsLoading(false); });
    return () => { cancelled = true; };
  }, [videoId, session?.userId]);

  function recordView() {
    if (!playback?.allowed || playback.videoId !== videoId || viewRecordedRef.current === videoId) return;
    viewRecordedRef.current = videoId;
    sendView(videoId)
      .then(counted => {
        // Not counted means this viewer was counted before: nothing to retry
        if (counted) setDetails(current => (current?.videoId === videoId ? { ...current, viewCount: current.viewCount + 1 } : current));
      })
      .catch(() => { if (viewRecordedRef.current === videoId) viewRecordedRef.current = null; });
  }

  function handlePlaying() {
    recordPlaybackHistory();
    recordView();
  }

  function recordAdView() {
    if (!activeAd || countedAdRef.current === activeAd) return;
    countedAdRef.current = activeAd;
    sendView(activeAd.adVideoId).catch(() => {});
  }

  useEffect(() => {
    const updateFullscreen = () => setIsFullscreen(document.fullscreenElement === playerWrapRef.current);
    document.addEventListener('fullscreenchange', updateFullscreen);
    return () => document.removeEventListener('fullscreenchange', updateFullscreen);
  }, []);

  useEffect(() => {
    let cancelled = false;
    let timer;
    setSelectedQuality(null);
    setQualityMode('auto');
    setQualityLevels([]);
    setQualitiesLoaded(false);
    setHlsQualities([]);
    setHlsPlayingQuality('');
    setHlsFailed(false);
    lastSwitchRef.current = 0;
    lastStallRef.current = 0;
    stallTimesRef.current = [];
    async function refreshProcessing() {
      try {
        const [videoResponse, qualityResponse] = await Promise.all([
          fetch('/api/videos'),
          fetch(`/api/videos/${videoId}/quality-levels`),
        ]);
        if (!videoResponse.ok || !qualityResponse.ok) throw new Error('Could not load quality options');
        const currentVideo = (await videoResponse.json()).find(item => item.videoId === videoId);
        const levels = await qualityResponse.json();
        if (cancelled) return;
        if (currentVideo) setProcessingStatus(currentVideo.processingStatus);
        setQualityLevels(current => (JSON.stringify(current) === JSON.stringify(levels) ? current : levels));
        setQualitiesLoaded(true);
        if (currentVideo?.processingStatus === 'PROCESSING') timer = setTimeout(refreshProcessing, 3000);
      } catch {
        if (cancelled) return;
        // Without quality information, play the uploaded file rather than nothing
        setQualitiesLoaded(true);
        timer = setTimeout(refreshProcessing, 5000);
      }
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

  const playbackAllowed = Boolean(playback?.allowed) && playback?.videoId === videoId;
  useEffect(() => {
    // Only a different video resets which ads were played (not a new token or session for the same one)
    playedBreaksRef.current = new Set();
    setPlayedBreaks(new Set());
  }, [videoId]);

  useEffect(() => {
    setAdBreaks([]);
    setActiveAd(null);
    if (!playbackAllowed) return undefined;
    let cancelled = false;
    fetch(`/api/videos/${videoId}/ad-breaks`, { credentials: 'include' })
      .then(response => (response.ok ? response.json() : []))
      .then(result => { if (!cancelled && Array.isArray(result)) setAdBreaks(result); })
      .catch(() => { /* Ads are optional: the video plays without them. */ });
    return () => { cancelled = true; };
  }, [videoId, playbackAllowed]);

  useEffect(() => {
    const ad = adRef.current;
    if (!activeAd || !ad) return;
    const main = videoRef.current;
    if (main) { ad.volume = main.volume; ad.muted = main.muted; }
    // Browsers may refuse to autoplay with sound; then play muted (the viewer can unmute)
    ad.play().catch(() => { ad.muted = true; ad.play().catch(() => {}); });
  }, [activeAd]);

  function handleTimeUpdate(event) {
    const player = event.currentTarget;
    setCurrentTime(player.currentTime);
    if (activeAd || !Number.isFinite(player.duration) || player.duration <= MIN_VIDEO_SECONDS_FOR_ADS) return;
    const crossed = adBreaks.map((adBreak, index) => index).filter(index => !playedBreaksRef.current.has(index)
      && player.currentTime >= adBreaks[index].position * player.duration);
    if (crossed.length === 0) return;
    // A seek past several markers plays one ad (the last one crossed), not several back to back; all count as played
    crossed.forEach(index => playedBreaksRef.current.add(index));
    startAd(crossed[crossed.length - 1]);
  }

  function startAd(index) {
    playedBreaksRef.current.add(index);
    setPlayedBreaks(new Set(playedBreaksRef.current));
    videoRef.current?.pause();
    if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
    setQualityMenuOpen(false);
    setAdRemaining(adBreaks[index].durationSeconds);
    setAdElapsed(0);
    setActiveAd(adBreaks[index]);
  }

  function finishAd() {
    setActiveAd(null);
    setAdPlaying(false);
    videoRef.current?.play().catch(() => {});
  }

  function updateAdRemaining(event) {
    const ad = event.currentTarget;
    const total = Number.isFinite(ad.duration) ? ad.duration : activeAd?.durationSeconds ?? 0;
    setAdRemaining(Math.max(0, Math.ceil(total - ad.currentTime)));
    setAdElapsed(ad.currentTime);
  }

  const adSkippable = Boolean(activeAd) && activeAd.durationSeconds > SKIPPABLE_AD_SECONDS;
  const skipCountdown = Math.max(0, Math.ceil(SKIP_AFTER_SECONDS - adElapsed));
  const showAdMarkers = duration > MIN_VIDEO_SECONDS_FOR_ADS;

  function toggleAdPlayback() {
    const ad = adRef.current;
    if (!ad) return;
    if (ad.paused) ad.play().catch(() => {});
    else ad.pause();
  }

  function toggleAdMute() {
    if (adRef.current) adRef.current.muted = !adRef.current.muted;
  }

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

  // Videos transcoded since HLS stream adaptively; older ones have single MP4 files per quality (Auto below)
  const hasHls = qualityLevels.some(level => level.format === 'hls');
  const hlsEngine = hasHls && !hlsFailed ? hlsSupport() : null;
  const streamingHls = hlsEngine === 'hlsjs' || hlsEngine === 'native';
  const masterUrl = `/api/videos/${videoId}/hls/master.m3u8${tokenQuery}`;
  const levels = useMemo(
    () => toLevels(qualityLevels.filter(level => level.format !== 'hls'), duration > 0 ? duration : durationSeconds(video?.duration)),
    [qualityLevels, duration, video?.duration],
  );
  const levelsKey = levels.map(level => level.quality).join(',');

  /** Loads `quality` at the current position (keeping play/pause). */
  async function switchSource(quality) {
    if (quality === autoRef.current.selectedQuality) return;
    // Tokens are short-lived; get a fresh one before switching source if it's about to expire
    if (playback?.expiresAt && Date.parse(playback.expiresAt) - Date.now() < 30_000) {
      try { setPlayback(await fetchPlayback(videoId)); } catch { /* keep the current token */ }
    }
    const player = videoRef.current;
    const started = autoRef.current.selectedQuality != null;
    playbackRef.current = player && started ? { time: player.currentTime, wasPlaying: !player.paused } : null;
    lastSwitchRef.current = Date.now();
    stallTimesRef.current = [];
    setSelectedQuality(quality);
  }

  // Auto's decisions run from timers and media events, so they read the latest state through this ref
  autoRef.current = { levels, qualityMode, selectedQuality, activeAd, switchSource };

  /** Auto: switch if the connection now calls for another quality. */
  function applyAutoQuality() {
    const { levels: currentLevels, qualityMode: mode, selectedQuality: current, activeAd: ad, switchSource: change } = autoRef.current;
    if (mode !== 'auto' || !currentLevels.length || ad) return;
    const playing = current && current !== 'source' ? current : null;
    const next = decideQuality({
      levels: currentLevels, current: playing, bandwidthKbps: bandwidthRef.current,
      lastSwitchAt: lastSwitchRef.current, lastStallAt: lastStallRef.current,
    });
    if (next && next !== current) change(next);
  }

  // HLS through hls.js: it measures every segment download and switches quality between segments, seamlessly
  useEffect(() => {
    const player = videoRef.current;
    if (hlsEngine !== 'hlsjs' || !playbackAllowed || !player) return undefined;
    if (playbackRef.current?.videoId !== undefined && playbackRef.current.videoId !== videoId) playbackRef.current = null;
    // Coming from the uploaded file (processing just finished) or a refreshed token: carry on from the same point
    if (!playbackRef.current && player.currentTime > 0) playbackRef.current = { time: player.currentTime, wasPlaying: !player.paused, videoId };
    let hls = null;
    let cancelled = false;
    let recoveries = 0;
    // Loaded on demand: most of the app doesn't need hls.js
    import('hls.js').then(({ default: Hls }) => {
      if (cancelled) return;
      const browserKbps = browserEstimateKbps();
      hls = new Hls({
        startLevel: -1,
        ...(browserKbps ? { abrEwmaDefaultEstimate: browserKbps * 1000 } : {}),
      });
      hlsRef.current = hls;
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        setHlsQualities(hls.levels.map((level, index) => ({ index, name: hlsLevelName(level), bitrate: level.bitrate || 0 })));
        const mode = autoRef.current.qualityMode;
        const fixed = mode && mode !== 'auto' ? hls.levels.findIndex(level => hlsLevelName(level) === mode) : -1;
        if (fixed >= 0) hls.currentLevel = fixed;
        if (!playbackRef.current) player.play().catch(() => {});
      });
      hls.on(Hls.Events.LEVEL_SWITCHED, (_event, data) => setHlsPlayingQuality(hlsLevelName(hls.levels[data.level])));
      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (!data.fatal) return;
        const status = data.response?.code;
        // The playback token expired (e.g. paused for hours): get a new one; this effect then restarts from the same point
        if (data.type === Hls.ErrorTypes.NETWORK_ERROR && (status === 401 || status === 403)) {
          recoverRef.current?.();
          return;
        }
        recoveries += 1;
        if (recoveries > MAX_HLS_RECOVERIES) {
          setHlsFailed(true);
        } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
          hls.recoverMediaError();
        } else if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
          hls.startLoad();
        } else {
          setHlsFailed(true);
        }
      });
      hls.loadSource(masterUrl);
      hls.attachMedia(player);
    }).catch(() => { if (!cancelled) setHlsFailed(true); });
    return () => {
      cancelled = true;
      if (!hls) return;
      if (!playbackRef.current && player.currentTime > 0) playbackRef.current = { time: player.currentTime, wasPlaying: !player.paused, videoId };
      hls.destroy();
      if (hlsRef.current === hls) hlsRef.current = null;
    };
  }, [hlsEngine, masterUrl, playbackAllowed, videoId]);

  // Before any transcoded quality exists (still processing), play the uploaded file
  useEffect(() => {
    if (qualitiesLoaded && levels.length === 0) setSelectedQuality(current => current ?? 'source');
  }, [qualitiesLoaded, levels.length]);

  // Auto: measure the connection now and every PROBE_INTERVAL_MS while the video plays
  useEffect(() => {
    if (qualityMode !== 'auto' || !levelsKey || !playbackAllowed) return undefined;
    const controller = new AbortController();
    if (!(bandwidthRef.current > 0)) bandwidthRef.current = browserEstimateKbps();
    let probing = false;
    async function probe(bytes) {
      if (probing) return;
      probing = true;
      try {
        const sample = await measureBandwidth(bytes, controller.signal);
        bandwidthRef.current = updateEstimate(bandwidthRef.current, sample);
      } catch { /* keep the previous estimate */ } finally { probing = false; }
      if (!controller.signal.aborted) applyAutoQuality();
    }
    // Start as soon as the first measurement is in, or after FIRST_CHOICE_TIMEOUT_MS with the best guess so far
    const firstChoice = setTimeout(() => { if (!controller.signal.aborted) applyAutoQuality(); }, FIRST_CHOICE_TIMEOUT_MS);
    probe(FIRST_PROBE_BYTES);
    const interval = setInterval(() => {
      const player = videoRef.current;
      if (!document.hidden && player && !player.paused) probe(PROBE_BYTES);
    }, PROBE_INTERVAL_MS);
    return () => { controller.abort(); clearTimeout(firstChoice); clearInterval(interval); };
    // applyAutoQuality reads everything else through autoRef
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [qualityMode, levelsKey, playbackAllowed, videoId]);

  /** Auto: buffering again and again means the connection can't keep up with this quality; go down a level. */
  function handleWaiting(event) {
    const player = event.currentTarget;
    const { levels: currentLevels, qualityMode: mode, selectedQuality: current } = autoRef.current;
    if (mode !== 'auto' || !current || current === 'source' || player.seeking || player.currentTime === 0) return;
    const now = Date.now();
    if (now - lastSwitchRef.current < SWITCH_SETTLE_MS) return;
    lastStallRef.current = now;
    stallTimesRef.current = [...stallTimesRef.current.filter(time => now - time < STALL_WINDOW_MS), now];
    if (stallTimesRef.current.length < STALLS_TO_DOWNGRADE) return;
    const lower = stepDown(currentLevels, current);
    if (lower === current) return;
    // Remember that the connection couldn't sustain this quality, so Auto doesn't climb straight back up
    const failed = currentLevels.find(level => level.quality === current);
    if (failed) bandwidthRef.current = Math.min(bandwidthRef.current ?? Infinity, failed.kbps / SAFETY_FACTOR * 0.9);
    switchSource(lower);
  }

  function selectQuality(mode) {
    setQualityMode(mode);
    setQualityMenuOpen(false);
    setSettingsPanel('root');
    if (hlsEngine === 'hlsjs') {
      const hls = hlsRef.current;
      if (!hls) return;
      // -1 hands the choice back to hls.js; a fixed level switches straight away
      hls.currentLevel = mode === 'auto' ? -1 : hlsQualities.find(quality => quality.name === mode)?.index ?? -1;
      return;
    }
    // MP4: Auto takes over from the next measurement (made straight away); a fixed quality applies now
    if (mode !== 'auto') switchSource(mode);
  }

  const playingQuality = hlsEngine === 'hlsjs' ? hlsPlayingQuality : selectedQuality && selectedQuality !== 'source' ? selectedQuality : '';
  const qualityLabel = qualityMode === 'auto' ? `Auto${playingQuality ? ` (${playingQuality})` : ''}` : qualityMode;
  // Highest first, like YouTube. Native HLS (Safari) chooses by itself and offers no menu.
  const menuQualities = hlsEngine === 'hlsjs'
    ? [...hlsQualities].sort((a, b) => b.bitrate - a.bitrate).map(quality => quality.name)
    : streamingHls ? [] : [...levels].sort((a, b) => qualityHeight(b.quality) - qualityHeight(a.quality)).map(level => level.quality);

  /** What the <video> element plays directly. hls.js feeds it through Media Source instead (no src). */
  function playerSource() {
    if (hlsEngine === 'hlsjs') return undefined;
    if (hlsEngine === 'native') return masterUrl;
    if (hasHls) return playback.sourceUrl;
    if (selectedQuality == null) return undefined;
    return selectedQuality === 'source' ? playback.sourceUrl : `/api/videos/${videoId}/renditions/${selectedQuality}${tokenQuery}`;
  }

  // Tokens last the video's length, but one can still expire (e.g. paused for hours). When a chunk
  // request is refused, get a fresh token and resume where the viewer was. If access has ended,
  // the new answer shows the locked screen instead.
  async function recoverFromPlaybackError() {
    // An older video moved to HLS (the backfill) loses its MP4 files: reload the qualities and carry on with HLS
    if (!hasHls && selectedQuality && selectedQuality !== 'source') reloadQualityLevels();
    if (!playback?.token || Date.now() - lastTokenRefreshRef.current < 30_000) return;
    lastTokenRefreshRef.current = Date.now();
    const player = videoRef.current;
    playbackRef.current = player ? { time: player.currentTime, wasPlaying: !player.paused } : null;
    try { setPlayback(await fetchPlayback(videoId)); } catch { /* leave the browser's error showing */ }
  }

  recoverRef.current = recoverFromPlaybackError;

  async function reloadQualityLevels() {
    if (Date.now() - lastQualityReloadRef.current < 30_000) return;
    lastQualityReloadRef.current = Date.now();
    try {
      const response = await fetch(`/api/videos/${videoId}/quality-levels`);
      if (!response.ok) return;
      const latest = await response.json();
      // The hls.js effect picks up from the player's current position
      if (latest.some(level => level.format === 'hls')) setQualityLevels(latest);
    } catch { /* the browser's error stays showing */ }
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
      {videosLoading ? <p className="watch-message">Loading video…</p> : videosError ? <p className="watch-message" role="alert">{videosError}</p> : !video ? <p className="watch-message">Video not found.</p> : (
        <div className={`watch-layout${theaterMode ? ' is-theater' : ''}`}>
          <section className="watch-primary">
            {processingStatus === 'PROCESSING' && <p className="watch-message" role="status">Preparing quality options… The video plays at its uploaded quality until they're ready.</p>}
            {processingStatus === 'FAILED' && <p className="watch-message" role="status">Quality processing failed. The video plays at its uploaded quality.</p>}
            {playbackError ? <p className="watch-message" role="alert">{playbackError}</p> : !playback ? <p className="watch-message">Checking access…</p> : !playback.allowed ? (
              <div className="locked-player" role="region" aria-label="Members-only video">
                <span className="members-badge">Members only</span>
                <h2>{video.title}</h2>
                <p>{LOCKED_MESSAGES[playback.reason] || 'You do not have access to this video.'}</p>
                {playback.reason !== 'SIGN_IN_REQUIRED' && <JoinButton channelId={video.channelId} requiredLevel={playback.requiredTierLevel || undefined} onOpenMemberships={onOpenMemberships} label="Join to watch" />}
              </div>
            ) : (
            <div ref={playerWrapRef} className={`watch-player-wrap${theaterMode ? ' is-theater' : ''}`}>
              <video ref={videoRef} className="watch-player" src={playerSource()} onClick={togglePlayback} onLoadedMetadata={event => { setDuration(event.currentTarget.duration); setCurrentTime(event.currentTarget.currentTime); restorePlayback(); }} onTimeUpdate={handleTimeUpdate} onDurationChange={event => setDuration(event.currentTarget.duration)} onPlay={() => setIsPlaying(true)} onPlaying={handlePlaying} onWaiting={handleWaiting} onPause={() => setIsPlaying(false)} onVolumeChange={event => { setVolume(event.currentTarget.volume); setIsMuted(event.currentTarget.muted); }} onEnded={() => { setIsPlaying(false); if (playlistQueue.next) onSelectVideo(playlistQueue.next.videoId, playlistQueue.next.video, playlistId); }} onError={recoverFromPlaybackError} onRateChange={event => setPlaybackRate(event.currentTarget.playbackRate)} poster={video.thumbnailUrl || undefined} autoPlay playsInline />
              {activeAd && <div className="watch-ad" role="region" aria-label="Advertisement">
                <video ref={adRef} className="watch-ad-player" src={activeAd.sourceUrl} playsInline onClick={toggleAdPlayback}
                  onTimeUpdate={updateAdRemaining} onLoadedMetadata={updateAdRemaining} onPlay={() => setAdPlaying(true)} onPlaying={recordAdView} onPause={() => setAdPlaying(false)}
                  onVolumeChange={event => setAdMuted(event.currentTarget.muted)} onEnded={finishAd} onError={finishAd} />
                <div className="watch-ad-bar">
                  <button type="button" className="watch-control-button" aria-label={adPlaying ? 'Pause ad' : 'Play ad'} onClick={toggleAdPlayback}><Icon name={adPlaying ? 'pause' : 'play'} /></button>
                  <button type="button" className="watch-control-button" aria-label={adMuted ? 'Unmute ad' : 'Mute ad'} onClick={toggleAdMute}><Icon name={adMuted ? 'mute' : 'volume'} /></button>
                  <span className="watch-ad-badge">Ad · {formatPlayerTime(adRemaining)}</span>
                  <span className="watch-ad-info">{activeAd.title} · {activeAd.channelName}</span>
                </div>
                {adSkippable && (skipCountdown > 0
                  ? <span className="watch-ad-skip is-waiting" aria-live="polite">Skip in {skipCountdown}</span>
                  : <button type="button" className="watch-ad-skip" onClick={finishAd}>Skip ad ›</button>)}
              </div>}
              {!isPlaying && !activeAd && <button type="button" className="watch-center-play" aria-label="Play video" onClick={togglePlayback}><Icon name="play" size={34} /></button>}
              {!activeAd && <div className="watch-controls">
                <div className="watch-seek-wrap">
                  <input className="watch-seek" type="range" min="0" max={duration || 0} step="0.1" value={Math.min(currentTime, duration || 0)} aria-label="Seek video" style={{ '--seek-progress': `${duration ? (currentTime / duration) * 100 : 0}%` }} onChange={event => { if (videoRef.current) videoRef.current.currentTime = Number(event.target.value); }} />
                  {showAdMarkers && adBreaks.map((adBreak, index) => !playedBreaks.has(index) && <span key={index} className="watch-ad-marker" style={{ left: `${adBreak.position * 100}%` }} title={`Ad at ${formatPlayerTime(adBreak.position * duration)}`} aria-hidden="true" />)}
                </div>
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
                          {menuQualities.length > 0 && <button type="button" role="menuitem" className="watch-settings-row" onClick={() => setSettingsPanel('quality')}><span>Quality</span><span>{qualityLabel} ›</span></button>}
                          <button type="button" role="menuitem" className="watch-settings-row" onClick={() => setSettingsPanel('speed')}><span>Playback speed</span><span>{playbackRate === 1 ? 'Normal' : `${playbackRate}x`} ›</span></button>
                        </> : settingsPanel === 'quality' ? <>
                          <button type="button" className="watch-settings-back" onClick={() => setSettingsPanel('root')}>‹ <span>Quality</span></button>
                          {menuQualities.map(quality => [quality, quality]).concat([['auto', qualityMode === 'auto' && playingQuality ? `Auto (${playingQuality})` : 'Auto']]).map(([mode, label]) => <button type="button" role="menuitemradio" aria-checked={qualityMode === mode} className="watch-settings-row" key={mode} onClick={() => selectQuality(mode)}><span>{qualityMode === mode ? '✓' : ''}</span><span>{label}</span></button>)}
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
              </div>}
            </div>
            )}
            <h1 className="watch-title">{video.visibility === 'MEMBERS_ONLY' && <span className="members-badge inline">Members only</span>}{video.title}</h1>
            <div className="watch-channel-row">
              <a className="watch-channel" href={`/channel/${video.channelId}`} onClick={event => { event.preventDefault(); onSelectChannel(video.channelId); }}>
                <span className="channel-avatar" aria-hidden="true">{video.channelName?.trim().charAt(0).toUpperCase() || '?'}</span>
                {video.channelName}
              </a>
              <JoinButton channelId={video.channelId} onOpenMemberships={onOpenMemberships} />
              <LikeButton key={`${videoId}:${session?.userId || 'current'}`} videoId={videoId} />
              <SaveButton key={`save:${videoId}:${session?.userId || 'current'}`} videoId={videoId} />
              <AddToPlaylistButton key={`playlist:${videoId}:${session?.userId || 'current'}`} videoId={videoId} />
            </div>
            <VideoDescription details={details?.videoId === videoId ? details : null} loading={detailsLoading} error={detailsError} />

            {playlistId && <PlaylistQueue playlistId={playlistId} videoId={videoId} queue={playlistQueue} onSelectVideo={onSelectVideo} onOpenPlaylist={onOpenPlaylist} />}

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
