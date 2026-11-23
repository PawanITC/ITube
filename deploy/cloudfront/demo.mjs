// Small acceptance test, not a load test. Never print signed URLs or private key material.
// Requires AWS CLI credentials restricted to the cdn-demo prefixes and the env vars used in cdn-demo.yml.
import { randomUUID, sign } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';
import { appendFileSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';

const env = process.env;
for (const name of ['CDN_BASE_URL', 'CDN_ASSETS_BUCKET', 'CDN_MEDIA_BUCKET', 'CDN_SIGNING_KEY_ID', 'CDN_SIGNING_PRIVATE_KEY']) {
  if (!env[name]) throw new Error(`Missing ${name}`);
}
const base = env.CDN_BASE_URL.replace(/\/+$/, '');
assert.match(base, /^https:\/\/[a-zA-Z0-9.-]+$/);
const id = randomUUID();
const assetKey = `assets/cdn-demo/${id}.txt`;
const mediaKey = `videos/cdn-demo/${id}/source`;
const bytes = Buffer.alloc(4096, 65);
const uploaded = [];
const report = [];

function record(message) { report.push(message); console.log(message); }
function aws(args, input) {
  return execFileSync('aws', [...args, '--region', 'eu-west-2'], { input, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] });
}
function signed(key, expires) {
  const resource = `${base}/${key}`;
  const policy = JSON.stringify({ Statement: [{ Resource: resource, Condition: { DateLessThan: { 'AWS:EpochTime': expires } } }] });
  const signature = sign('RSA-SHA1', Buffer.from(policy), env.CDN_SIGNING_PRIVATE_KEY).toString('base64')
    .replaceAll('+', '-').replaceAll('=', '_').replaceAll('/', '~');
  return `${resource}?Expires=${expires}&Signature=${signature}&Key-Pair-Id=${env.CDN_SIGNING_KEY_ID}`;
}
async function request(label, url, status, headers = {}) {
  // Catch without including URL (a URL may contain a playback credential).
  let response;
  try { response = await fetch(url, { headers, redirect: 'manual', signal: AbortSignal.timeout(20000) }); }
  catch { throw new Error(`${label}: request failed`); }
  assert.equal(response.status, status, `${label}: unexpected HTTP status`);
  const body = Buffer.from(await response.arrayBuffer());
  record(`${label}: HTTP ${status}; X-Cache=${response.headers.get('x-cache') ?? '-'}; Age=${response.headers.get('age') ?? '-'}; PoP=${response.headers.get('x-amz-cf-pop') ?? '-'}`);
  return { response, body };
}
async function requireHit(label, url, headers) {
  for (let attempt = 0; attempt < 5; attempt++) {
    const result = await request(label, url, 200, headers);
    assert.deepEqual(result.body, bytes);
    if (result.response.headers.get('x-cache')?.includes('Hit from cloudfront')) return result;
    await sleep(1000);
  }
  throw new Error(`${label}: no cache hit observed after five small requests`);
}

try {
  for (const [bucket, key] of [[env.CDN_ASSETS_BUCKET, assetKey], [env.CDN_MEDIA_BUCKET, mediaKey]]) {
    aws(['s3', 'cp', '-', `s3://${bucket}/${key}`, '--content-type', 'application/octet-stream', '--cache-control', 'public,max-age=3600', '--only-show-errors'], bytes);
    uploaded.push([bucket, key]);
  }
  await requireHit('Public asset origin', `${base}/${assetKey}`);
  const expires = Math.floor(Date.now() / 1000) + 120;
  const first = signed(mediaKey, expires);
  const second = signed(mediaKey, expires + 1);
  const warm = await requireHit('Signed media origin', first);
  const result = await request('Different valid signature shares cached bytes', second, 200, { Origin: 'https://itube.fun' });
  assert.deepEqual(result.body, bytes);
  assert.equal(result.response.headers.get('x-amz-cf-pop'), warm.response.headers.get('x-amz-cf-pop'));
  assert.match(result.response.headers.get('x-cache'), /Hit from cloudfront/);
  assert.equal(result.response.headers.get('access-control-allow-origin'), 'https://itube.fun');
  assert.match(result.response.headers.get('cache-control'), /no-store/);
  const range = await request('Video seeking / range', second, 206, { Range: 'bytes=0-15' });
  assert.equal(range.response.headers.get('content-range'), 'bytes 0-15/4096');
  assert.deepEqual(range.body, bytes.subarray(0, 16));
  await request('Unsigned media denied even after cache warmup', `${base}/${mediaKey}`, 403);
  await request('Tampered signed path denied', first.replace('/source?', '/other?'), 403);
  await request('Expired signature denied', signed(mediaKey, Math.floor(Date.now() / 1000) - 60), 403);
  for (const [bucket, key] of uploaded) {
    await request('Direct anonymous S3 denied', `https://${bucket}.s3.eu-west-2.amazonaws.com/${key}`, 403);
  }
  record('PASS: two origins, shared edge cache, signature enforcement, expiry, range reads, CORS, private S3.');
  record('This single-runner test does not prove multi-location latency or production capacity.');
} catch {
  // No stack/URL/key in CI logs. Individual HTTP observations above identify the failing stage.
  process.exitCode = 1;
  record('FAIL: acceptance checks did not all pass. Review the last HTTP observation and configuration.');
} finally {
  for (const [bucket, key] of uploaded) {
    try { aws(['s3', 'rm', `s3://${bucket}/${key}`, '--only-show-errors']); }
    catch { process.exitCode = 1; record('Cleanup failed for a demo object; remove the cdn-demo prefix objects manually.'); }
  }
  if (env.GITHUB_STEP_SUMMARY) appendFileSync(env.GITHUB_STEP_SUMMARY, report.map(s => `- ${s}\n`).join(''));
}
