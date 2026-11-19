// Resumable video uploads (backend: UploadController). The file goes up as numbered parts, normally
// straight to object storage through pre-signed URLs. Object storage keeps the parts that arrived, so an
// upload that was paused, lost its connection or had its tab closed continues where it stopped.

const CONCURRENT_PARTS = 4;
const MAX_ATTEMPTS = 5;
const URL_BATCH = 20;
const URL_REFRESH_MARGIN_MS = 60_000;

export class UploadError extends Error {
  constructor(message, { fatal = false, status } = {}) {
    super(message);
    this.fatal = fatal;
    this.status = status;
  }
}

function abortError() {
  return new DOMException('Upload paused', 'AbortError');
}

export function isAbort(error) {
  return error?.name === 'AbortError';
}

async function api(path, { method = 'GET', json, body, signal } = {}) {
  const response = await fetch(path, {
    method,
    credentials: 'include',
    signal,
    headers: json ? { 'Content-Type': 'application/json' } : undefined,
    body: json ? JSON.stringify(json) : body,
  });
  if (response.status === 401) {
    throw new UploadError('Your session expired. Click the ITube logo to start a new session.', { fatal: true, status: 401 });
  }
  if (!response.ok) {
    let problem;
    try { problem = await response.json(); } catch { /* The response may not contain JSON. */ }
    const fatal = [400, 403, 404, 409, 410, 413, 415].includes(response.status);
    throw new UploadError(problem?.detail || 'The upload server had a problem. Please try again.', { fatal, status: response.status });
  }
  return response.status === 204 ? null : response.json();
}

export const uploadsApi = {
  listOpen: signal => api('/api/uploads', { signal }),
  get: (uploadId, signal) => api(`/api/uploads/${uploadId}`, { signal }),
  start: details => api('/api/uploads', { method: 'POST', json: details }),
  discard: uploadId => api(`/api/uploads/${uploadId}`, { method: 'DELETE' }),
  complete: (uploadId, thumbnail) => {
    const form = new FormData();
    if (thumbnail) form.append('thumbnail', thumbnail, thumbnail.name || 'thumbnail.jpg');
    return api(`/api/uploads/${uploadId}/complete`, { method: 'POST', body: form });
  },
};

/** True if `file` is the file the upload was started with. */
export function isSameFile(file, upload) {
  return file.name === upload.fileName && file.size === upload.fileSize && file.lastModified === upload.lastModified;
}

function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(abortError()); return; }
    const timer = setTimeout(() => { signal.removeEventListener('abort', onAbort); resolve(); }, ms);
    function onAbort() { clearTimeout(timer); reject(abortError()); }
    signal.addEventListener('abort', onAbort, { once: true });
  });
}

function putPart(target, blob, onProgress, signal) {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(abortError()); return; }
    const request = new XMLHttpRequest();
    request.open('PUT', target.url);
    // Pre-signed object storage URLs must not get the site's cookies; backend URLs need them
    request.withCredentials = target.viaBackend;
    if (target.viaBackend) request.setRequestHeader('Content-Type', 'application/octet-stream');
    request.upload.onprogress = event => onProgress(event.loaded);
    const onAbort = () => request.abort();
    signal.addEventListener('abort', onAbort, { once: true });
    const finish = () => signal.removeEventListener('abort', onAbort);
    request.onload = () => {
      finish();
      if (request.status >= 200 && request.status < 300) { resolve(); return; }
      if (target.viaBackend && [401, 404, 410].includes(request.status)) {
        let problem;
        try { problem = JSON.parse(request.responseText); } catch { /* The response may not contain JSON. */ }
        reject(new UploadError(problem?.detail || 'This upload can no longer be resumed.', { fatal: true, status: request.status }));
        return;
      }
      // e.g. 403 from object storage when a pre-signed URL expired: retried with a fresh URL
      reject(new UploadError(`Part upload failed (${request.status})`, { status: request.status }));
    };
    request.onerror = () => { finish(); reject(new UploadError('Network error')); };
    request.onabort = () => { finish(); reject(abortError()); };
    request.send(blob);
  });
}

/**
 * Sends the parts object storage doesn't have yet. `upload` is an UploadSessionDto (from start or get).
 * onProgress(fraction) reports overall progress. Rejects with an AbortError when `signal` aborts (pause).
 */
export async function uploadMissingParts(file, upload, { onProgress, signal }) {
  const local = new AbortController();
  const stop = () => local.abort();
  if (signal.aborted) throw abortError();
  signal.addEventListener('abort', stop, { once: true });

  const done = new Set(upload.uploadedParts);
  const pending = [];
  for (let part = 1; part <= upload.partCount; part += 1) if (!done.has(part)) pending.push(part);
  let finishedBytes = upload.uploadedBytes;
  const inFlight = new Map();
  const report = () => {
    let sending = 0;
    inFlight.forEach(bytes => { sending += bytes; });
    onProgress?.(Math.min(1, (finishedBytes + sending) / upload.fileSize));
  };
  report();

  const urls = new Map();
  let next = 0;
  async function urlFor(part) {
    const cached = urls.get(part);
    if (cached && (!cached.expiresAt || Date.parse(cached.expiresAt) - Date.now() > URL_REFRESH_MARGIN_MS)) return cached;
    const batch = [part, ...pending.slice(next).filter(p => p !== part && !urls.has(p)).slice(0, URL_BATCH - 1)];
    const targets = await api(`/api/uploads/${upload.uploadId}/part-urls`, {
      method: 'POST', json: { partNumbers: batch }, signal: local.signal,
    });
    targets.forEach(target => urls.set(target.partNumber, target));
    return urls.get(part);
  }

  async function worker() {
    while (next < pending.length) {
      const part = pending[next];
      next += 1;
      const start = (part - 1) * upload.partSize;
      const end = Math.min(start + upload.partSize, upload.fileSize);
      for (let attempt = 1; ; attempt += 1) {
        try {
          const target = await urlFor(part);
          await putPart(target, file.slice(start, end), loaded => { inFlight.set(part, loaded); report(); }, local.signal);
          break;
        } catch (error) {
          inFlight.delete(part);
          report();
          if (isAbort(error) || local.signal.aborted) throw abortError();
          if (error.fatal || attempt >= MAX_ATTEMPTS) throw error;
          urls.delete(part);
          await sleep(1000 * 2 ** attempt, local.signal);
        }
      }
      inFlight.delete(part);
      finishedBytes += end - start;
      report();
    }
  }

  try {
    await Promise.all(Array.from({ length: Math.min(CONCURRENT_PARTS, pending.length) }, () =>
      worker().catch(error => { local.abort(); throw error; })));
  } catch (error) {
    if (signal.aborted) throw abortError();
    if (isAbort(error)) throw new UploadError('The upload stopped. Press Resume to continue.');
    throw error;
  } finally {
    signal.removeEventListener('abort', stop);
  }
}
