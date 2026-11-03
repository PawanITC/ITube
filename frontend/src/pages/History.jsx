import { useEffect, useRef, useState } from 'react';
import { clearHistory, getHistory, removeHistory } from '../api/history';
import Icon from '../components/Icon';
import './History.css';

const PAGE_SIZE = 20;
const dateFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

export default function History({ onHome }) {
  const [entries, setEntries] = useState([]);
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [confirmClear, setConfirmClear] = useState(false);
  const mounted = useRef(false);
  const mutationPending = useRef(false);
  const cancelButton = useRef(null);
  const clearButton = useRef(null);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError('');
    getHistory(page, PAGE_SIZE, controller.signal).then(data => {
      if (controller.signal.aborted) return;
      // Removing the last entry on a later page should return to the previous page.
      if (data.length === 0 && page > 0) {
        setPage(previous => previous - 1);
        return;
      }
      setEntries(data);
      setLoading(false);
    }).catch(reason => {
      if (controller.signal.aborted) return;
      setError(reason.message || 'Unable to load history. Please try again.');
      setLoading(false);
    });
    return () => controller.abort();
  }, [page, revision]);

  useEffect(() => { if (confirmClear) cancelButton.current?.focus(); }, [confirmClear]);

  function cancelClear() {
    setConfirmClear(false);
    clearButton.current?.focus();
  }

  async function mutate(operation, message, clear = false) {
    if (mutationPending.current) return;
    mutationPending.current = true;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await operation();
      if (!mounted.current) return;
      setConfirmClear(false);
      setNotice(message);
      if (clear) setPage(0);
      setRevision(value => value + 1);
    } catch (reason) {
      if (mounted.current) setError(reason.message || 'Unable to update history. Please try again.');
    } finally {
      mutationPending.current = false;
      if (mounted.current) setBusy(false);
    }
  }

  const disabled = loading || busy;

  return <section className="history-page" aria-labelledby="history-heading">
    <div className="history-heading">
      <div><p className="history-eyebrow">YOUR LIBRARY</p><h1 id="history-heading">Watch history</h1><p>Your recently watched videos, all in one place.</p></div>
      <div className="history-actions">
        <button className="history-button" disabled={disabled} onClick={() => { setNotice(''); setRevision(value => value + 1); }}>Refresh</button>
        <button ref={clearButton} className="history-button history-danger" disabled={disabled || entries.length === 0 || !!error} onClick={() => setConfirmClear(true)}>Clear all history</button>
      </div>
    </div>

    {confirmClear && <section className="history-confirm" aria-labelledby="clear-heading" onKeyDown={event => { if (event.key === 'Escape' && !busy) cancelClear(); }}>
      <h2 id="clear-heading">Clear your entire watch history?</h2>
      <p>This removes every entry, including entries on other pages. It cannot be undone.</p>
      <div className="history-actions"><button ref={cancelButton} className="history-button" disabled={busy} onClick={cancelClear}>Cancel</button><button className="history-button history-danger" disabled={busy} onClick={() => mutate(clearHistory, 'Your watch history has been cleared.', true)}>{busy ? 'Clearing…' : 'Confirm clear all'}</button></div>
    </section>}

    <p className="history-notice" role="status">{notice}</p>
    {error && <div className="history-error" role="alert"><p>{error}</p><button className="history-button" disabled={disabled} onClick={() => setRevision(value => value + 1)}>Retry</button></div>}
    {loading ? <p className="history-loading" role="status">Loading your history…</p> : !error && entries.length === 0 ? <div className="history-empty"><Icon name="history" size={52} /><h2>Your story starts here</h2><p>Videos you watch will appear here.</p><a className="history-button" href="/" onClick={onHome ? event => { event.preventDefault(); onHome(); } : undefined}>Explore videos</a></div> : <div aria-busy={busy}>
      {!error && <><div className="history-list-label"><h2>Recently watched</h2><span>Newest first</span></div><ul className="history-list">
        {entries.map(entry => <li className="history-entry" key={entry.id}>
          <div className="history-thumbnail" aria-hidden="true"><Icon name="play" size={32} /></div>
          <div className="history-entry-copy"><h3>Video {entry.videoId}</h3><p>Video details will appear when available.</p><time dateTime={entry.watchedAt}>Watched {dateFormatter.format(new Date(entry.watchedAt))}</time></div>
          <button className="history-remove" disabled={disabled || confirmClear} aria-label={`Remove video ${entry.videoId} from history`} onClick={() => mutate(() => removeHistory(entry.id), `Video ${entry.videoId} removed from history.`)}>Remove</button>
        </li>)}
      </ul></>}
    </div>}
    {!loading && !error && entries.length > 0 && <nav className="history-pagination" aria-label="History pages"><button className="history-button" disabled={disabled || confirmClear || page === 0} onClick={() => { setNotice(''); setPage(value => value - 1); }}>Previous</button><span>Page {page + 1}</span><button className="history-button" disabled={disabled || confirmClear || entries.length < PAGE_SIZE} onClick={() => { setNotice(''); setPage(value => value + 1); }}>Next</button></nav>}
  </section>;
}
