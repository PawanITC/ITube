import { useEffect, useRef, useState } from 'react';

function formatDuration(seconds) {
  const totalSeconds = Math.round(seconds);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const remainingSeconds = totalSeconds % 60;
  return [hours, minutes, remainingSeconds].map(value => String(value).padStart(2, '0')).join(':');
}

function readVideoMetadataAndThumbnail(file) {
  return new Promise((resolve, reject) => {
    const video = document.createElement('video');
    const objectUrl = URL.createObjectURL(file);
    let duration;
    video.preload = 'metadata';
    video.muted = true;
    video.onloadedmetadata = () => {
      duration = video.duration;
      if (!Number.isFinite(duration) || duration <= 0 || duration >= 24 * 60 * 60) {
        URL.revokeObjectURL(objectUrl);
        reject(new Error('Could not read a supported video duration (under 24 hours).'));
        return;
      }
      video.currentTime = duration / 2;
    };
    video.onseeked = () => {
      try {
        const canvas = document.createElement('canvas');
        const scale = Math.min(1, 640 / video.videoWidth);
        canvas.width = Math.max(1, Math.round(video.videoWidth * scale));
        canvas.height = Math.max(1, Math.round(video.videoHeight * scale));
        canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height);
        canvas.toBlob(blob => {
          URL.revokeObjectURL(objectUrl);
          if (!blob) reject(new Error('Could not create a thumbnail from this video. Choose an image thumbnail instead.'));
          else resolve({ duration, thumbnail: blob });
        }, 'image/jpeg', 0.85);
      } catch {
        URL.revokeObjectURL(objectUrl);
        reject(new Error('Could not create a thumbnail from this video. Choose an image thumbnail instead.'));
      }
    };
    video.onerror = () => {
      URL.revokeObjectURL(objectUrl);
      reject(new Error('Could not read the selected video. Choose an image thumbnail instead.'));
    };
    video.src = objectUrl;
  });
}

export default function UploadVideoPage({ session, onCancel }) {
  const [activeSession, setActiveSession] = useState(session);
  const [sessionLoading, setSessionLoading] = useState(!session);
  const [file, setFile] = useState(null);
  const [thumbnail, setThumbnail] = useState(null);
  const [thumbnailPreview, setThumbnailPreview] = useState('');
  const [title, setTitle] = useState('');
  const [duration, setDuration] = useState('');
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const fileInput = useRef(null);
  const thumbnailInput = useRef(null);

  useEffect(() => {
    if (!thumbnail) {
      setThumbnailPreview('');
      return;
    }
    const url = URL.createObjectURL(thumbnail);
    setThumbnailPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [thumbnail]);

  useEffect(() => {
    if (session) return;
    let cancelled = false;
    fetch('/api/session/current', { credentials: 'include' })
      .then(response => {
        if (!response.ok) throw new Error('No active session');
        return response.json();
      })
      .then(currentSession => {
        if (!cancelled) setActiveSession(currentSession);
      })
      .catch(() => {
        if (!cancelled) setError('Click the ITube logo to start a session before uploading.');
      })
      .finally(() => {
        if (!cancelled) setSessionLoading(false);
      });
    return () => { cancelled = true; };
  }, [session]);

  function selectFile(event) {
    const selectedFile = event.target.files?.[0] || null;
    setFile(selectedFile);
    setThumbnail(null);
    setDuration('');
    setError('');
    setSuccess('');
    if (!selectedFile) return;

    readVideoMetadataAndThumbnail(selectedFile)
      .then(({ duration: seconds, thumbnail: generatedThumbnail }) => {
        setDuration(formatDuration(seconds));
        setThumbnail(generatedThumbnail);
      })
      .catch(thumbnailError => setError(thumbnailError.message));
  }

  function selectThumbnail(event) {
    const selectedThumbnail = event.target.files?.[0] || null;
    if (selectedThumbnail && !selectedThumbnail.type.startsWith('image/')) {
      setError('Choose an image file for the thumbnail.');
      event.target.value = '';
      return;
    }
    setThumbnail(selectedThumbnail);
    setError('');
  }

  async function submitVideo(event) {
    event.preventDefault();
    setError('');
    setSuccess('');
    if (!activeSession) {
      setError('Click the ITube logo to start a session before uploading.');
      return;
    }
    if (!file || !duration || !title.trim()) {
      setError('Choose a video, wait for its duration to load, and enter a title.');
      return;
    }

    const form = new FormData();
    form.append('file', file);
    form.append('title', title.trim());
    form.append('duration', duration);
    if (thumbnail) form.append('thumbnail', thumbnail, thumbnail.name || 'thumbnail.jpg');

    setUploading(true);
    try {
      const response = await fetch('/api/videos', {
        method: 'POST',
        credentials: 'include',
        body: form,
      });
      if (response.status === 401) throw new Error('Your session expired. Click the ITube logo to start a new session.');
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail || 'Upload failed. Please try again.');
      }
      setSuccess('Your video was posted. Quality options are processing in the background.');
      setFile(null);
      setTitle('');
      setDuration('');
      setThumbnail(null);
      if (fileInput.current) fileInput.current.value = '';
      if (thumbnailInput.current) thumbnailInput.current.value = '';
    } catch (uploadError) {
      setError(uploadError.message || 'Upload failed. Please try again.');
    } finally {
      setUploading(false);
    }
  }

  return (
    <section className="upload-page">
      <button className="upload-back" type="button" onClick={onCancel}>← Back to home</button>
      <div className="upload-card">
        <h1>Upload video</h1>
        <p className="upload-description">Add a video to channel #{activeSession?.channelId ?? '…'}.</p>
        {sessionLoading ? <p role="status">Checking your session…</p> : (
          <form className="upload-form" onSubmit={submitVideo}>
            <label className="upload-file-label" htmlFor="video-file">Video file</label>
            <input ref={fileInput} id="video-file" type="file" accept="video/*" onChange={selectFile} />
            {file && <p className="upload-file-name">{file.name}{duration ? ` · ${duration}` : ' · Reading duration…'}</p>}

            <label htmlFor="video-thumbnail">Thumbnail (optional)</label>
            <input ref={thumbnailInput} id="video-thumbnail" type="file" accept="image/*" onChange={selectThumbnail} />
            <p className="upload-file-name">If you do not choose an image, a frame from the middle of the video will be used.</p>
            {thumbnailPreview && <img className="upload-thumbnail-preview" src={thumbnailPreview} alt="Video thumbnail preview" />}

            <label htmlFor="video-title">Title</label>
            <input id="video-title" type="text" maxLength="255" value={title}
              onChange={event => setTitle(event.target.value)} placeholder="Add a title for your video" required />

            {error && <p className="upload-error" role="alert">{error}</p>}
            {success && <p className="upload-success" role="status">{success}</p>}

            <button className="upload-submit" type="submit" disabled={uploading || sessionLoading || !activeSession}>
              {uploading ? 'Posting…' : 'Post video'}
            </button>
          </form>
        )}
        {!sessionLoading && !activeSession && <button type="button" className="upload-back" onClick={onCancel}>Return home</button>}
      </div>
    </section>
  );
}
