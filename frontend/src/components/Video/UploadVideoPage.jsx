import { useCallback, useEffect, useRef, useState } from 'react';
import { isAbort, isSameFile, uploadMissingParts, uploadsApi } from './resumableUpload';

function formatBytes(bytes) {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)} GB`;
  if (bytes >= 1024 ** 2) return `${Math.round(bytes / 1024 ** 2)} MB`;
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

function formatDate(isoDate) {
  return new Date(isoDate).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' });
}

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
  const [visibility, setVisibility] = useState('PUBLIC');
  const [requiredTierLevel, setRequiredTierLevel] = useState('');
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [uploadingTitle, setUploadingTitle] = useState('');
  const [openUploads, setOpenUploads] = useState([]);
  const [resumeTarget, setResumeTarget] = useState(null);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const fileInput = useRef(null);
  const thumbnailInput = useRef(null);
  const resumeInput = useRef(null);
  const uploadAbort = useRef(null);

  const refreshOpenUploads = useCallback(() => {
    uploadsApi.listOpen()
      .then(setOpenUploads)
      .catch(() => { /* The list is a convenience; uploading still works without it. */ });
  }, []);

  useEffect(() => {
    if (activeSession) refreshOpenUploads();
  }, [activeSession, refreshOpenUploads]);

  // Leaving the page pauses the upload; it can be resumed later from "Unfinished uploads"
  useEffect(() => () => uploadAbort.current?.abort(), []);

  useEffect(() => {
    if (!uploading) return undefined;
    const warn = event => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [uploading]);

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

    if (uploading) return;
    setUploading(true);
    let upload;
    try {
      // Picking a file that already has an unfinished upload continues that upload
      upload = await uploadsApi.start({
        fileName: file.name,
        fileSize: file.size,
        lastModified: file.lastModified,
        contentType: file.type,
        title: title.trim(),
        duration,
        visibility,
        requiredTierLevel: visibility === 'MEMBERS_ONLY' && requiredTierLevel ? Number(requiredTierLevel) : null,
      });
    } catch (startError) {
      setError(startError.message || 'Upload failed. Please try again.');
      setUploading(false);
      return;
    }
    await runUpload(upload, file, thumbnail);
  }

  /** Sends the parts of `upload` that object storage doesn't have yet, then posts the video. */
  async function runUpload(upload, videoFile, videoThumbnail) {
    const controller = new AbortController();
    uploadAbort.current = controller;
    setUploading(true);
    setUploadingTitle(upload.title);
    setUploadProgress(Math.floor((upload.uploadedBytes / upload.fileSize) * 100));
    setError('');
    setSuccess('');
    try {
      const current = await uploadsApi.get(upload.uploadId, controller.signal);
      await uploadMissingParts(videoFile, current, {
        signal: controller.signal,
        onProgress: fraction => setUploadProgress(Math.min(99, Math.floor(fraction * 100))),
      });
      await uploadsApi.complete(upload.uploadId, videoThumbnail);
      setUploadProgress(100);
      setSuccess('Your video was posted. Quality options are processing in the background.');
      setFile(null);
      setTitle('');
      setDuration('');
      setThumbnail(null);
      if (fileInput.current) fileInput.current.value = '';
      if (thumbnailInput.current) thumbnailInput.current.value = '';
    } catch (uploadError) {
      if (isAbort(uploadError)) {
        setSuccess(`Upload paused. You can resume it until ${formatDate(upload.expiresAt)}, even after leaving this page.`);
      } else if (uploadError.fatal) {
        setError(uploadError.message);
      } else {
        setError(`${uploadError.message || 'Upload failed'}. Your progress is saved: press Resume to continue.`);
      }
    } finally {
      if (uploadAbort.current === controller) uploadAbort.current = null;
      setUploading(false);
      refreshOpenUploads();
    }
  }

  function pauseUpload() {
    uploadAbort.current?.abort();
  }

  function chooseFileToResume(upload) {
    setError('');
    setSuccess('');
    setResumeTarget(upload);
    if (resumeInput.current) {
      resumeInput.current.value = '';
      resumeInput.current.click();
    }
  }

  async function resumeWithFile(event) {
    const chosen = event.target.files?.[0];
    const upload = resumeTarget;
    setResumeTarget(null);
    if (!chosen || !upload) return;
    if (!isSameFile(chosen, upload)) {
      setError(`That isn't the same file. Choose "${upload.fileName}" (${formatBytes(upload.fileSize)}), unchanged since you started uploading it.`);
      return;
    }
    // Thumbnails aren't kept between visits, so take a frame from the video again
    const generated = await readVideoMetadataAndThumbnail(chosen).then(result => result.thumbnail).catch(() => null);
    await runUpload(upload, chosen, generated);
  }

  async function discardUpload(upload) {
    if (!window.confirm(`Discard the unfinished upload "${upload.title}"? The parts uploaded so far will be deleted.`)) return;
    setError('');
    setSuccess('');
    try {
      await uploadsApi.discard(upload.uploadId);
    } catch (discardError) {
      setError(discardError.message);
    }
    refreshOpenUploads();
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

            <label htmlFor="video-visibility">Visibility</label>
            <select id="video-visibility" value={visibility} onChange={event => setVisibility(event.target.value)}>
              <option value="PUBLIC">Public</option>
              <option value="UNLISTED">Unlisted (anyone with the link)</option>
              <option value="PRIVATE">Private (only you)</option>
              <option value="MEMBERS_ONLY">Members only</option>
            </select>
            {visibility === 'MEMBERS_ONLY' && <>
              <label htmlFor="video-tier">Minimum membership level</label>
              <select id="video-tier" value={requiredTierLevel} onChange={event => setRequiredTierLevel(event.target.value)}>
                <option value="">Any paid tier</option>
                {[1, 2, 3, 4, 5].map(level => <option key={level} value={level}>Level {level} and above</option>)}
              </select>
            </>}

            {error && <p className="upload-error" role="alert">{error}</p>}
            {success && <p className="upload-success" role="status">{success}</p>}
            {uploading && <div className="upload-progress" role="status" aria-live="polite">
              <div className="upload-progress-label"><span>Uploading {uploadingTitle ? `"${uploadingTitle}"` : 'video'}</span><span>{uploadProgress}%</span></div>
              <progress aria-label="Video upload progress" max="100" value={uploadProgress} />
              <button type="button" className="upload-pause" onClick={pauseUpload}>Pause upload</button>
            </div>}

            <button className="upload-submit" type="submit" disabled={uploading || sessionLoading || !activeSession}>
              {uploading ? 'Posting…' : 'Post video'}
            </button>
          </form>
        )}
        {!sessionLoading && activeSession && openUploads.length > 0 && <section className="upload-unfinished" aria-labelledby="unfinished-heading">
          <h2 id="unfinished-heading">Unfinished uploads</h2>
          <p className="upload-file-name">To continue one, choose the same video file again.</p>
          <input ref={resumeInput} type="file" accept="video/*" hidden onChange={resumeWithFile} aria-label="Video file to resume" />
          <ul>
            {openUploads.map(upload => <li key={upload.uploadId}>
              <div className="upload-unfinished-info">
                <strong>{upload.title}</strong>
                <span className="upload-file-name">
                  {upload.fileName} · {Math.floor((upload.uploadedBytes / upload.fileSize) * 100)}% of {formatBytes(upload.fileSize)} · resume by {formatDate(upload.expiresAt)}
                </span>
                <progress aria-label={`${upload.title} upload progress`} max={upload.fileSize} value={upload.uploadedBytes} />
              </div>
              <div className="upload-unfinished-actions">
                <button type="button" className="upload-submit" disabled={uploading} onClick={() => chooseFileToResume(upload)}>Resume</button>
                <button type="button" className="edit-video-cancel" disabled={uploading} onClick={() => discardUpload(upload)}>Discard</button>
              </div>
            </li>)}
          </ul>
        </section>}
        {!sessionLoading && !activeSession && <button type="button" className="upload-back" onClick={onCancel}>Return home</button>}
      </div>
    </section>
  );
}
