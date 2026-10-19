import { useEffect, useMemo, useState } from 'react';
import Icon from './Icon';
import VideoCard from './VideoCard';

function formatCommentDate(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

export default function WatchPage({ videoId, videos, videosLoading, videosError, session, onSelectVideo, onSelectChannel, onHome }) {
  const [comments, setComments] = useState([]);
  const [commentsLoading, setCommentsLoading] = useState(true);
  const [commentsError, setCommentsError] = useState('');
  const [hasSession, setHasSession] = useState(Boolean(session));
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const video = useMemo(() => videos.find(item => item.videoId === videoId), [videos, videoId]);
  const recommendations = useMemo(() => videos.filter(item => item.videoId !== videoId), [videos, videoId]);

  useEffect(() => {
    if (session) setHasSession(true);
  }, [session]);

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

  return (
    <main className="watch-main">
      <button type="button" className="watch-back" onClick={onHome}>‹ Back to videos</button>
      {videosLoading ? <p className="watch-message">Loading video…</p> : videosError ? <p className="watch-message" role="alert">{videosError}</p> : !video ? <p className="watch-message">Video not found.</p> : (
        <div className="watch-layout">
          <section className="watch-primary">
            <video className="watch-player" src={video.videoUrl} poster={video.thumbnailUrl || undefined} controls autoPlay playsInline />
            <h1 className="watch-title">{video.title}</h1>
            <a className="watch-channel" href={`/channel/${video.channelId}`} onClick={event => { event.preventDefault(); onSelectChannel(video.channelId); }}>{video.channelName}</a>

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
