import { useEffect, useLayoutEffect, useRef, useState } from 'react';

const viewFormatter = new Intl.NumberFormat();

export function formatViews(count) {
  const views = Number(count) || 0;
  return `${viewFormatter.format(views)} ${views === 1 ? 'view' : 'views'}`;
}

const UNITS = [
  ['year', 365 * 24 * 3600],
  ['month', 30 * 24 * 3600],
  ['week', 7 * 24 * 3600],
  ['day', 24 * 3600],
  ['hour', 3600],
  ['minute', 60],
];

export function formatAge(value, now = Date.now()) {
  const time = new Date(value).getTime();
  if (!Number.isFinite(time)) return '';
  const seconds = Math.max(0, Math.floor((now - time) / 1000));
  for (const [unit, size] of UNITS) {
    const amount = Math.floor(seconds / size);
    if (amount >= 1) return `${amount} ${unit}${amount === 1 ? '' : 's'} ago`;
  }
  return 'just now';
}

/** The box between the like row and the comments: views, upload age and the (expandable) description. */
export default function VideoDescription({ details, loading, error }) {
  const [expanded, setExpanded] = useState(false);
  const [overflows, setOverflows] = useState(false);
  const textRef = useRef(null);
  const description = details?.description || '';

  useEffect(() => { setExpanded(false); }, [details?.videoId]);

  // Only offer "...more" when the clamped text is actually cut off
  useLayoutEffect(() => {
    const element = textRef.current;
    if (!element || expanded) return;
    setOverflows(element.scrollHeight > element.clientHeight + 1);
  }, [description, expanded]);

  if (loading && !details) return <section className="watch-description is-loading" aria-label="Video description"><p className="watch-description-meta">Loading details…</p></section>;
  if (error && !details) return null;
  if (!details) return null;

  const canToggle = overflows || expanded;
  return (
    <section className={`watch-description${canToggle && !expanded ? ' is-collapsed' : ''}`} aria-label="Video description"
      onClick={canToggle && !expanded ? () => setExpanded(true) : undefined}>
      <p className="watch-description-meta">
        <span>{formatViews(details.viewCount)}</span>
        {details.createdAt && <time dateTime={details.createdAt} title={new Date(details.createdAt).toLocaleString()}>{formatAge(details.createdAt)}</time>}
      </p>
      {description
        ? <p ref={textRef} className={`watch-description-text${expanded ? ' is-expanded' : ''}`}>{description}</p>
        : <p className="watch-description-text is-empty">No description has been added.</p>}
      {canToggle && <button type="button" className="watch-description-toggle" aria-expanded={expanded}
        onClick={event => { event.stopPropagation(); setExpanded(open => !open); }}>{expanded ? 'Show less' : '...more'}</button>}
    </section>
  );
}
