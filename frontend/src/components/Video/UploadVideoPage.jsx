import { useCallback, useEffect, useRef, useState } from 'react';
import Icon from '../Icon';
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

/** Ads can be at most this long (the backend checks it too). */
const MAX_AD_SECONDS = 60;
const MAX_DESCRIPTION_LENGTH = 5000;
/** Ads only play in videos longer than this. */
const MIN_VIDEO_SECONDS_FOR_ADS = 60;
/** A video may have one ad per 10 minutes, and at least one (the backend checks the same rule). */
const SECONDS_PER_AD = 600;

function maxAdsFor(durationSeconds) {
  return durationSeconds === null ? 1 : Math.max(1, Math.floor(durationSeconds / SECONDS_PER_AD));
}

const UPLOAD_TYPES = [
  { value: 'VIDEO', label: 'Video' },
  { value: 'AD', label: 'Ad' },
];

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
  const [description, setDescription] = useState('');
  const [duration, setDuration] = useState('');
  const [durationSeconds, setDurationSeconds] = useState(null);
  const [videoType, setVideoType] = useState('VIDEO');
  const [adsEnabled, setAdsEnabled] = useState(true);
  const [adCount, setAdCount] = useState(1);
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
    setDurationSeconds(null);
    setError('');
    setSuccess('');
    if (!selectedFile) return;

    readVideoMetadataAndThumbnail(selectedFile)
      .then(({ duration: seconds, thumbnail: generatedThumbnail }) => {
        setDuration(formatDuration(seconds));
        setDurationSeconds(Math.round(seconds));
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

  const isAd = videoType === 'AD';
  const adTooLong = isAd && durationSeconds !== null && durationSeconds > MAX_AD_SECONDS;
  const maxAds = maxAdsFor(durationSeconds);

  function selectVideoType(type) {
    if (uploading) return;
    setVideoType(type);
    setError('');
    setSuccess('');
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
      setError(`Choose a ${isAd ? 'video file for the ad' : 'video'}, wait for its duration to load, and enter a title.`);
      return;
    }
    if (adTooLong) {
      setError(`Ads can be at most 1 minute long. This one is ${duration}.`);
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
        description: description.trim() || null,
        duration,
        visibility,
        requiredTierLevel: visibility === 'MEMBERS_ONLY' && requiredTierLevel ? Number(requiredTierLevel) : null,
        videoType,
        adsEnabled: !isAd && adsEnabled,
        adCount: !isAd && adsEnabled ? Math.min(adCount, maxAds) : null,
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
      setSuccess(`Your ${upload.videoType === 'AD' ? 'ad' : 'video'} was posted. Quality options are processing in the background.`);
      setFile(null);
      setTitle('');
      setDescription('');
      setDuration('');
      setDurationSeconds(null);
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
      <div className="upload-card studio-upload">
        <header className="studio-header">
          <span className="studio-upload-icon"><Icon name="upload" size={26} /></span>
          <div>
            <h1>{isAd ? 'Upload ad' : 'Upload video'}</h1>
            {activeSession && <p className="upload-description">Channel #{activeSession.channelId}</p>}
          </div>
        </header>
        {sessionLoading ? <p role="status">Checking your session…</p> : <>
          <div className="upload-type-tabs" role="tablist" aria-label="What are you uploading?">
            {UPLOAD_TYPES.map(type => <button key={type.value} type="button" role="tab" id={`upload-tab-${type.value}`}
              aria-selected={videoType === type.value} aria-controls="upload-form"
              className={videoType === type.value ? 'upload-type-tab active' : 'upload-type-tab'}
              disabled={uploading} onClick={() => selectVideoType(type.value)}>{type.label}</button>)}
          </div>
          <form id="upload-form" className="upload-form" role="tabpanel" aria-labelledby={`upload-tab-${videoType}`} onSubmit={submitVideo}>
            <div className="studio-file">
              <Icon name="upload" size={30} />
              <label className="upload-file-label" htmlFor="video-file">{isAd ? 'Ad video file' : 'Video file'}</label>
              <input ref={fileInput} id="video-file" type="file" accept="video/*" onChange={selectFile} />
            </div>
            {isAd && <p className="upload-file-name">Ads can be at most 1 minute long.</p>}
            {file && <p className="upload-file-name">{file.name}{duration ? ` · ${duration}` : ' · Reading duration…'}</p>}
            {adTooLong && <p className="upload-error" role="alert">This video is {duration} long. Ads can be at most 1 minute: choose a shorter video or upload it as a normal video.</p>}

            <div className="studio-layout">
              <section className="studio-details" aria-labelledby="upload-details-heading">
                <h2 id="upload-details-heading">Details</h2>
                <label htmlFor="video-title">Title</label>
                <input id="video-title" type="text" maxLength="255" value={title}
                  onChange={event => setTitle(event.target.value)} placeholder={isAd ? 'Add a title for your ad' : 'Add a title for your video'} required />

                <label htmlFor="video-description">Description (optional)</label>
                <textarea id="video-description" className="upload-description-input" rows="5" maxLength={MAX_DESCRIPTION_LENGTH} value={description}
                  onChange={event => setDescription(event.target.value)} placeholder={isAd ? 'Tell viewers about your ad' : 'Tell viewers about your video'} />
                <p className="upload-file-name upload-char-count">{description.length}/{MAX_DESCRIPTION_LENGTH}</p>

                {!isAd && <label className="upload-checkbox" htmlFor="video-ads">
                  <input id="video-ads" type="checkbox" checked={adsEnabled} onChange={event => setAdsEnabled(event.target.checked)} />
                  <span>Show ads in this video</span>
                </label>}
                {!isAd && adsEnabled && <>
                  <label htmlFor="video-ad-count">Number of ads</label>
                  <select id="video-ad-count" value={Math.min(adCount, maxAds)} onChange={event => setAdCount(Number(event.target.value))}>
                    {Array.from({ length: maxAds }, (_, index) => index + 1).map(count => <option key={count} value={count}>
                      {count === 1 ? '1 ad' : `${count} ads`}
                    </option>)}
                  </select>
                  <p className="upload-file-name">
                    {durationSeconds !== null && durationSeconds <= MIN_VIDEO_SECONDS_FOR_ADS
                      ? 'Ads only play in videos longer than 1 minute.'
                      : `One ad per 10 minutes of video${durationSeconds !== null ? `: up to ${maxAds} for this one` : ''}. Ads are spread evenly through the video.`}
                  </p>
                </>}

              </section>
              <section className="studio-preview" aria-labelledby="upload-preview-heading">
                <h2 id="upload-preview-heading">Preview &amp; visibility</h2>
                <div className="studio-preview-frame">
                  {thumbnailPreview
                    ? <img src={thumbnailPreview} alt="Video thumbnail preview" />
                    : <><Icon name="myVideos" size={35} /><span>No thumbnail selected</span></>}
                </div>
                <label htmlFor="video-thumbnail">Thumbnail (optional)</label>
                <input ref={thumbnailInput} id="video-thumbnail" type="file" accept="image/*" onChange={selectThumbnail} />
                <p className="upload-file-name">If you do not choose an image, a frame from the middle of the video will be used.</p>
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
              </section>
            </div>

            <div className="studio-footer">
              {error && <p className="upload-error" role="alert">{error}</p>}
              {success && <p className="upload-success" role="status">{success}</p>}
              {uploading && <div className="upload-progress" role="status" aria-live="polite">
                <div className="upload-progress-label"><span>Uploading {uploadingTitle ? `"${uploadingTitle}"` : 'video'}</span><span>{uploadProgress}%</span></div>
                <progress aria-label="Video upload progress" max="100" value={uploadProgress} />
                <button type="button" className="upload-pause" onClick={pauseUpload}>Pause upload</button>
              </div>}

              <button className="upload-submit" type="submit" disabled={uploading || sessionLoading || !activeSession || adTooLong}>
                {uploading ? 'Posting…' : isAd ? 'Post ad' : 'Post video'}
              </button>
            </div>
          </form>
        </>}
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
