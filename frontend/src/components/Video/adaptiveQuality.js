/**
 * Automatic video quality ("Auto"): measures the viewer's connection to ITube and picks the best transcoded
 * quality it can sustain without stalling.
 *
 * Videos are plain MP4 renditions (360p, 720p), so switching means loading the other file at the same time.
 * The player therefore starts on a safe choice, goes DOWN as soon as the connection can't keep up (a bandwidth
 * drop or repeated buffering), and only goes UP once the connection has proved itself for a while, so it doesn't
 * keep flipping between qualities.
 */

/** Used when a rendition's size is unknown: typical bitrates for the transcoder's settings (H.264, CRF 23). */
export const NOMINAL_KBPS = { '144p': 200, '240p': 450, '360p': 900, '480p': 1500, '720p': 3000, '1080p': 6000, '1440p': 12000, '2160p': 25000 };

/** Only pick a quality whose bitrate is at most this share of the measured bandwidth (room for audio, overhead and dips). */
export const SAFETY_FACTOR = 0.75;
/** Wait at least this long after a switch before going UP again. */
export const UPGRADE_COOLDOWN_MS = 20_000;
/** ...and only if the player hasn't stalled for this long. */
export const STALL_QUIET_MS = 30_000;
/** This many stalls within STALL_WINDOW_MS means the current quality is too much: go down one level. */
export const STALLS_TO_DOWNGRADE = 2;
export const STALL_WINDOW_MS = 15_000;
/** How often to re-measure while the video plays. */
export const PROBE_INTERVAL_MS = 30_000;
export const FIRST_PROBE_BYTES = 512 * 1024;
export const PROBE_BYTES = 256 * 1024;

export function qualityHeight(quality) {
  const height = parseInt(quality, 10);
  return Number.isFinite(height) ? height : 0;
}

/**
 * [{ quality, sizeBytes }] from the server -> [{ quality, kbps }], lowest first. The bitrate comes from the
 * file size and the video's length when both are known; otherwise from NOMINAL_KBPS.
 */
export function toLevels(qualityLevels, durationSeconds) {
  return [...(qualityLevels || [])]
    .map(level => {
      const measured = level.sizeBytes > 0 && durationSeconds > 0 ? (level.sizeBytes * 8) / 1000 / durationSeconds : null;
      const nominal = NOMINAL_KBPS[level.quality] ?? qualityHeight(level.quality) * 4;
      return { quality: level.quality, kbps: measured ?? nominal };
    })
    .sort((a, b) => qualityHeight(a.quality) - qualityHeight(b.quality));
}

/** The highest quality whose bitrate fits in the bandwidth (after SAFETY_FACTOR); the lowest if none does. */
export function pickQuality(levels, bandwidthKbps) {
  if (!levels.length) return null;
  if (!(bandwidthKbps > 0)) return levels[0].quality;
  const budget = bandwidthKbps * SAFETY_FACTOR;
  const fitting = levels.filter(level => level.kbps <= budget);
  return (fitting.length ? fitting[fitting.length - 1] : levels[0]).quality;
}

/** One level below `quality` (or itself if it is already the lowest). */
export function stepDown(levels, quality) {
  const index = levels.findIndex(level => level.quality === quality);
  return index > 0 ? levels[index - 1].quality : levels[0]?.quality ?? quality;
}

/** The browser's own guess (Chrome/Edge/Android), in kbps; null where it isn't available. */
export function browserEstimateKbps() {
  const downlink = typeof navigator !== 'undefined' ? navigator.connection?.downlink : undefined;
  return downlink > 0 ? downlink * 1000 : null;
}

/** Downloads `bytes` of throwaway data from ITube and returns the measured bandwidth in kbps. */
export async function measureBandwidth(bytes, signal) {
  const started = performance.now();
  const response = await fetch(`/api/videos/bandwidth-probe?bytes=${bytes}&t=${Date.now()}`, { cache: 'no-store', signal });
  if (!response.ok) throw new Error(`Bandwidth probe failed (${response.status})`);
  const body = await response.arrayBuffer();
  const seconds = Math.max((performance.now() - started) / 1000, 0.001);
  return (body.byteLength * 8) / 1000 / seconds;
}

/**
 * Smooths measurements so one slow or fast sample doesn't swing the choice; drops are taken more seriously
 * than rises (a falling connection must be reacted to quickly, a rising one can wait).
 */
export function updateEstimate(previousKbps, sampleKbps) {
  if (!(previousKbps > 0)) return sampleKbps;
  const weight = sampleKbps < previousKbps ? 0.6 : 0.3;
  return previousKbps + (sampleKbps - previousKbps) * weight;
}

/**
 * What Auto should play now. `current` is the quality playing (null before the first choice). Downgrades are
 * immediate; upgrades wait for UPGRADE_COOLDOWN_MS since the last switch and STALL_QUIET_MS since the last stall.
 */
export function decideQuality({ levels, current, bandwidthKbps, lastSwitchAt, lastStallAt, now = Date.now() }) {
  const target = pickQuality(levels, bandwidthKbps);
  if (!target || !current || !levels.some(level => level.quality === current)) return target;
  const goingUp = qualityHeight(target) > qualityHeight(current);
  if (!goingUp) return target;
  const settled = now - (lastSwitchAt ?? 0) >= UPGRADE_COOLDOWN_MS && now - (lastStallAt ?? 0) >= STALL_QUIET_MS;
  return settled ? target : current;
}
