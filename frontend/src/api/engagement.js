// Mutations return 202 when Kafka accepts them. Keep the existing UI busy until ScyllaDB confirms the write.
const pending = new Set();
let account;
let generation = 0;

// Keep a previous account's outstanding writes out of the next account's loading/polling flow.
export function setEngagementAccount(userId) {
  if (account !== userId) { account = userId; generation++; pending.clear(); }
}
const delay = (milliseconds, signal) => new Promise((resolve, reject) => {
  if (signal?.aborted) { reject(new DOMException('Aborted', 'AbortError')); return; }
  const abort = () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')); };
  const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve(); }, milliseconds);
  signal?.addEventListener('abort', abort, { once: true });
});

export async function waitForOperation(operationId, signal, timeout = 15000, scope = generation) {
  const deadline = Date.now() + timeout;
  const controller = new AbortController();
  const cancel = () => controller.abort();
  signal?.addEventListener('abort', cancel, { once: true });
  if (signal?.aborted) cancel();
  const timer = setTimeout(cancel, timeout);
  let interval = 250;
  try {
    while (Date.now() < deadline) {
      if (scope !== generation) throw new DOMException('Account changed', 'AbortError');
      if (controller.signal.aborted) throw new DOMException('Aborted', 'AbortError');
      const response = await fetch(`/api/engagement/operations/${encodeURIComponent(operationId)}`, {
        credentials: 'include', signal: controller.signal, cache: 'no-store',
      });
      if (!response.ok) {
        const error = new Error(`Could not check this update (${response.status}). It may still complete. Refresh the page to check.`);
        error.status = response.status; error.operationId = operationId; throw error;
      }
      const receipt = await response.json();
      if (scope !== generation) throw new DOMException('Account changed', 'AbortError');
      if (receipt.state === 'SUCCEEDED') return receipt.result;
      if (receipt.state === 'FAILED') {
        const error = new Error(receipt.message || 'This update could not be completed.');
        error.status = receipt.httpStatus; error.operationId = operationId; throw error;
      }
      await delay(Math.min(interval, Math.max(0, deadline - Date.now())), controller.signal);
      interval = Math.min(1000, interval * 2);
    }
  } catch (error) {
    if (signal?.aborted || scope !== generation || Date.now() < deadline) throw error;
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', cancel);
  }
  const error = new Error('Your update is queued and is taking longer than usual. Refresh to check before submitting it again.');
  error.pending = true; error.operationId = operationId; throw error;
}

export async function engagementFetch(url, options = {}) {
  const method = options.method || 'GET';
  if (method === 'GET') {
    // Navigating straight from the player to History must wait for the watch already submitted by this tab.
    if (pending.size) await Promise.all([...pending]);
    return fetch(url, options);
  }
  const headers = { ...options.headers, 'Idempotency-Key': options.headers?.['Idempotency-Key'] || crypto.randomUUID() };
  const scope = generation;
  const operation = (async () => {
    let response;
    // A lost acknowledgement can mean the send succeeded. One network retry MUST reuse the same operation id.
    for (let attempt = 0; attempt < 2; attempt++) {
      try { response = await fetch(url, { ...options, headers }); break; }
      catch (error) { if (error.name === 'AbortError' || options.signal?.aborted || attempt === 1) throw error; }
    }
    if (response.status !== 202) return response;
    if (scope !== generation) throw new DOMException('Account changed', 'AbortError');
    const receipt = await response.json();
    const result = receipt.state === 'SUCCEEDED' ? receipt.result
      : await waitForOperation(receipt.operationId, options.signal, 15000, scope);
    // Adapt the completed operation to the existing API helpers; components receive their original result shape.
    return new Response(result == null ? null : JSON.stringify(result), {
      status: result == null ? 204 : 200, headers: { 'Content-Type': 'application/json' },
    });
  })();
  pending.add(operation);
  try { return await operation; }
  finally { pending.delete(operation); }
}
