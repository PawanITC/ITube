// Shared helpers for the ITube k6 load tests: seeding test data and one "user visit" made of real API calls.
import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';

export const BASE = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const PASSWORD = 'load-test-password-1';
const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };
// A few bytes standing in for a video file: the load test measures the API, not transcoding
const FAKE_VIDEO = new Uint8Array([0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109]).buffer;

function ok(response, name, statuses = [200]) {
  const passed = check(response, { [`${name} ${statuses.join('/')}`]: (r) => statuses.includes(r.status) });
  if (!passed && __ENV.DEBUG) console.warn(`${name} -> ${response.status} ${response.body}`);
  return passed;
}

function register(label, run) {
  const email = `${label}-${run}@load.itube.test`;
  const response = http.post(`${BASE}/api/auth/register`, JSON.stringify({
    firstname: 'Load', lastname: label, email, password: PASSWORD, channelName: `Load ${label} ${run}`,
  }), JSON_HEADERS);
  if (!ok(response, 'register', [201])) fail(`registration failed (${response.status}): is the gateway rate limit raised?`);
  return { email, channelId: response.json('channelId') };
}

/**
 * Creates creators with videos (spread over the catalog shards by their user ids) and viewer accounts.
 * Runs once in k6's setup(); the result is shared with every virtual user.
 */
export function seed(creators, videosPerCreator, viewers) {
  const run = `${Date.now()}`;
  const videoIds = [];
  const channelIds = [];
  for (let c = 0; c < creators; c++) {
    const creator = register(`creator${c}`, run); // the session cookie now belongs to this creator
    channelIds.push(creator.channelId);
    for (let v = 0; v < videosPerCreator; v++) {
      const upload = http.post(`${BASE}/api/videos`, {
        file: http.file(FAKE_VIDEO, 'clip.mp4', 'video/mp4'),
        title: `Load test video ${c}-${v}`,
        duration: '00:01:00',
        visibility: 'PUBLIC',
      });
      ok(upload, 'seed upload', [201]);
    }
    for (const video of http.get(`${BASE}/api/videos/mine`).json() || []) videoIds.push(video.videoId);
  }
  const viewerEmails = [];
  for (let v = 0; v < viewers; v++) viewerEmails.push(register(`viewer${v}`, run).email);
  http.cookieJar().clear(BASE);
  if (videoIds.length === 0) fail('No videos were seeded');
  return { videoIds, channelIds, viewerEmails };
}

function signInOnce(data) {
  // Each virtual user signs in on its first iteration and then keeps its session cookie (its own cookie jar)
  if (exec.vu.metrics.tags.signedIn === 'yes') return;
  const email = data.viewerEmails[(exec.vu.idInTest - 1) % data.viewerEmails.length];
  const response = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email, password: PASSWORD }),
    { ...JSON_HEADERS, tags: { name: 'login' } });
  if (ok(response, 'login')) exec.vu.metrics.tags.signedIn = 'yes';
}

const THUMBNAIL_OK = http.expectedStatuses({ min: 200, max: 399 }, 404);
const pick = (list) => list[Math.floor(Math.random() * list.length)];

/** One visit, weighted like real traffic: mostly browsing and watching, some engagement, a little library use. */
export function visit(data) {
  signInOnce(data);
  const videoId = pick(data.videoIds);
  const roll = Math.random();
  const noRedirects = (name) => ({ redirects: 0, tags: { name } });

  if (roll < 0.55) {
    // Browse: home feed (cached in Caffeine and at the CDN), a channel page, thumbnails
    ok(http.get(`${BASE}/api/videos?limit=50`, { tags: { name: 'feed' } }), 'feed');
    ok(http.get(`${BASE}/api/channels/${pick(data.channelIds)}`, { tags: { name: 'channel' } }), 'channel');
    // Seeded videos have no thumbnail: 404 is an expected answer here, not a failure
    ok(http.get(`${BASE}/api/videos/${videoId}/thumbnail`, { ...noRedirects('thumbnail'), responseCallback: THUMBNAIL_OK }),
      'thumbnail', [302, 404]);
  } else if (roll < 0.85) {
    // Watch: who may play it (cached), the file (a redirect to storage/CDN), record it in History (ScyllaDB)
    ok(http.get(`${BASE}/api/videos/${videoId}/playback-authorisation`, { tags: { name: 'playback-auth' } }), 'playback-auth');
    ok(http.get(`${BASE}/api/videos/${videoId}/file`, noRedirects('file')), 'file', [200, 206, 302]);
    ok(http.post(`${BASE}/api/history`, JSON.stringify({ videoId }), { ...JSON_HEADERS, tags: { name: 'history-add' } }),
      'history-add', [201]);
    ok(http.get(`${BASE}/api/videos/${videoId}/comments?limit=20`, { tags: { name: 'comments' } }), 'comments');
  } else if (roll < 0.95) {
    // Engage: like, sometimes comment
    ok(http.put(`${BASE}/api/liked-videos/${videoId}`, null, { tags: { name: 'like' } }), 'like', [204]);
    if (Math.random() < 0.3) {
      ok(http.post(`${BASE}/api/videos/${videoId}/comments`, JSON.stringify({ content: 'Load test comment' }),
        { ...JSON_HEADERS, tags: { name: 'comment-add' } }), 'comment-add', [201]);
    }
  } else {
    // Library: the user's own lists (History and Liked need the catalog for titles: batched + cached)
    ok(http.get(`${BASE}/api/history?size=20`, { tags: { name: 'history' } }), 'history');
    ok(http.get(`${BASE}/api/liked-videos?size=20`, { tags: { name: 'liked' } }), 'liked');
    ok(http.get(`${BASE}/api/channels/subscriptions`, { tags: { name: 'subscriptions' } }), 'subscriptions');
  }
}

/** Pass/fail criteria shared by the scenarios (k6 exits non-zero when one is missed). */
export const thresholds = {
  http_req_failed: ['rate<0.01'],
  http_req_duration: ['p(95)<500', 'p(99)<1500'],
  'http_req_duration{name:feed}': ['p(95)<300'],
  'http_req_duration{name:playback-auth}': ['p(95)<300'],
  checks: ['rate>0.99'],
};
