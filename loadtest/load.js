// Load test: ramps to TARGET_RPS user visits per second (each visit is 1-4 API calls), holds it, ramps down.
// An "arrival rate" executor starts visits at a fixed rate whether or not the system keeps up, like real users do,
// so a slow system shows up as growing latency and dropped iterations instead of being hidden.
//
//   k6 run -e TARGET_RPS=200 -e HOLD=3m loadtest/load.js
import { seed, visit, thresholds } from './lib/itube.js';

const target = Number(__ENV.TARGET_RPS || 100);

export const options = {
  // Keep each virtual user's session cookie between iterations (k6 clears cookies after every iteration by default)
  noCookiesReset: true,
  scenarios: {
    load: {
      executor: 'ramping-arrival-rate',
      startRate: Math.max(1, Math.floor(target / 10)),
      timeUnit: '1s',
      preAllocatedVUs: Number(__ENV.VUS || 100),
      maxVUs: Number(__ENV.MAX_VUS || 1000),
      stages: [
        { target, duration: __ENV.RAMP || '1m' },
        { target, duration: __ENV.HOLD || '2m' },
        { target: 0, duration: '30s' },
      ],
    },
  },
  thresholds,
};

export function setup() {
  return seed(Number(__ENV.CREATORS || 6), Number(__ENV.VIDEOS || 10), Number(__ENV.VIEWERS || 50));
}

export default function (data) {
  visit(data);
}
