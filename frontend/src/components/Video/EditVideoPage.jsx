import { useEffect, useRef, useState } from 'react';

async function readProblem(response, fallback) {
  try {
    const problem = await response.json();
    return problem?.detail || fallback;
  } catch {
    return fallback;
  }
}

export default function EditVideoPage({ videoId, onBack, onDeleted }) {
  const [video, setVideo] = useState(null);
  const [loading, setLoading] = useState(true);
  const [title, setTitle] = useState('');
  const [visibility, setVisibility] = useState('PUBLIC');
  const [requiredTierLevel, setRequiredTierLevel] = useState('');
  const [thumbnail, setThumbnail] = useState(null);
  const [thumbnailPreview, setThumbnailPreview] = useState('');
  const [thumbnailVersion, setThumbnailVersion] = useState(0);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const thumbnailInput = useRef(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    fetch(`/api/videos/mine/${videoId}`, { credentials: 'include' })
      .then(async response => {
        if (response.status === 401) throw new Error('Click the ITube logo to start a session first.');
        if (response.status === 404) throw new Error('This video does not exist or is not yours.');
        if (!response.ok) throw new Error(await readProblem(response, 'Could not load this video.'));
        return response.json();
      })
      .then(loaded => {
        if (cancelled) return;
        setVideo(loaded);
        setTitle(loaded.title);
        setVisibility(loaded.visibility);
        setRequiredTierLevel(loaded.requiredTierLevel ? String(loaded.requiredTierLevel) : '');
      })
      .catch(loadError => { if (!cancelled) setError(loadError.message || 'Could not load this video.'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [videoId]);

  useEffect(() => {
    if (!thumbnail) {
      setThumbnailPreview('');
      return;
    }
    const url = URL.createObjectURL(thumbnail);
    setThumbnailPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [thumbnail]);

  function selectThumbnail(event) {
    const selected = event.target.files?.[0] || null;
    if (selected && !selected.type.startsWith('image/')) {
      setError('Choose an image file for the thumbnail.');
      event.target.value = '';
      return;
    }
    setThumbnail(selected);
    setError('');
    setSuccess('');
  }

  async function saveChanges(event) {
    event.preventDefault();
    setError('');
    setSuccess('');
    if (!title.trim()) {
      setError('Enter a title for your video.');
      return;
    }
    const form = new FormData();
    form.append('title', title.trim());
    form.append('visibility', visibility);
    if (visibility === 'MEMBERS_ONLY' && requiredTierLevel) form.append('requiredTierLevel', requiredTierLevel);
    if (thumbnail) form.append('thumbnail', thumbnail, thumbnail.name || 'thumbnail.jpg');

    setSaving(true);
    try {
      const response = await fetch(`/api/videos/${videoId}`, { method: 'PUT', body: form, credentials: 'include' });
      if (response.status === 401) throw new Error('Your session expired. Click the ITube logo to start a new session.');
      if (!response.ok) throw new Error(await readProblem(response, 'Could not save your changes.'));
      const updated = await response.json();
      setVideo(updated);
      setTitle(updated.title);
      setVisibility(updated.visibility);
      setRequiredTierLevel(updated.requiredTierLevel ? String(updated.requiredTierLevel) : '');
      if (thumbnail) setThumbnailVersion(version => version + 1);
      setThumbnail(null);
      if (thumbnailInput.current) thumbnailInput.current.value = '';
      setSuccess('Your changes were saved.');
    } catch (saveError) {
      setError(saveError.message || 'Could not save your changes.');
    } finally {
      setSaving(false);
    }
  }

  async function deleteVideo() {
    setError('');
    setSuccess('');
    setDeleting(true);
    try {
      const response = await fetch(`/api/videos/${videoId}`, { method: 'DELETE', credentials: 'include' });
      if (response.status === 401) throw new Error('Your session expired. Click the ITube logo to start a new session.');
      if (!response.ok) throw new Error(await readProblem(response, 'Could not delete this video.'));
      onDeleted();
    } catch (deleteError) {
      setError(deleteError.message || 'Could not delete this video.');
      setConfirmDelete(false);
      setDeleting(false);
    }
  }

  const currentThumbnail = video?.thumbnailUrl ? `${video.thumbnailUrl}?v=${thumbnailVersion}` : '/thumbnail-placeholder.svg';
  const busy = saving || deleting;

  return (
    <section className="upload-page">
      <button className="upload-back" type="button" onClick={onBack}>← Back to My Videos</button>
      <div className="upload-card">
        <h1>Edit video</h1>
        <p className="upload-description">{video ? `${video.channelName} · ${video.duration}` : 'Update your video’s details.'}</p>
        {loading ? <p role="status">Loading video…</p> : !video ? (
          <p className="upload-error" role="alert">{error}</p>
        ) : (
          <form className="upload-form" onSubmit={saveChanges}>
            <label htmlFor="edit-video-thumbnail">Thumbnail</label>
            <img className="upload-thumbnail-preview" src={thumbnailPreview || currentThumbnail} alt="Video thumbnail" />
            <input ref={thumbnailInput} id="edit-video-thumbnail" type="file" accept="image/*" onChange={selectThumbnail} disabled={busy} />
            <p className="upload-file-name">{thumbnail ? 'New thumbnail selected. Save to apply it.' : 'Choose an image to replace the current thumbnail.'}</p>

            <label htmlFor="edit-video-title">Title</label>
            <input id="edit-video-title" type="text" maxLength="255" value={title} disabled={busy}
              onChange={event => setTitle(event.target.value)} placeholder="Add a title for your video" required />

            <label htmlFor="edit-video-visibility">Visibility</label>
            <select id="edit-video-visibility" value={visibility} disabled={busy} onChange={event => setVisibility(event.target.value)}>
              <option value="PUBLIC">Public</option>
              <option value="UNLISTED">Unlisted (anyone with the link)</option>
              <option value="PRIVATE">Private (only you)</option>
              <option value="MEMBERS_ONLY">Members only</option>
            </select>
            {visibility === 'MEMBERS_ONLY' && <>
              <label htmlFor="edit-video-tier">Minimum membership level</label>
              <select id="edit-video-tier" value={requiredTierLevel} disabled={busy} onChange={event => setRequiredTierLevel(event.target.value)}>
                <option value="">Any paid tier</option>
                {[1, 2, 3, 4, 5].map(level => <option key={level} value={level}>Level {level} and above</option>)}
              </select>
            </>}

            {error && <p className="upload-error" role="alert">{error}</p>}
            {success && <p className="upload-success" role="status">{success}</p>}

            {confirmDelete ? (
              <div className="edit-video-confirm" role="alertdialog" aria-label="Confirm delete">
                <p>Delete “{video.title}”? This permanently removes the video, its comments and its quality versions. This cannot be undone.</p>
                <div className="edit-video-actions">
                  <button type="button" className="edit-video-cancel" onClick={() => setConfirmDelete(false)} disabled={deleting}>Cancel</button>
                  <button type="button" className="edit-video-delete" onClick={deleteVideo} disabled={deleting}>{deleting ? 'Deleting…' : 'Delete forever'}</button>
                </div>
              </div>
            ) : (
              <div className="edit-video-actions">
                <button type="button" className="edit-video-delete" onClick={() => setConfirmDelete(true)} disabled={busy}>Delete video</button>
                <button className="upload-submit" type="submit" disabled={busy}>{saving ? 'Saving…' : 'Save changes'}</button>
              </div>
            )}
          </form>
        )}
      </div>
    </section>
  );
}
