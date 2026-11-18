// Spike test: normal traffic, then a sudden jump to SPIKE_RPS (a video goes viral, a big creator uploads),
// then back. Checks that the system bends instead of breaking: autoscaling, caches, bulkheads and circuit
// breakers should keep errors low, and latency should recover after the spike.
//   k6 run -e BASE_RPS=50 -e SPIKE_RPS=500 loadtest/spike.js
import { seed, visit, thresholds } from './lib/itube.js';

const base = Number(__ENV.BASE_RPS || 50);
const spike = Number(__ENV.SPIKE_RPS || 500);

export const options = {
  // Keep each virtual user's session cookie between iterations (k6 clears cookies after every iteration by default)
  noCookiesReset: true,
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: base,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: Number(__ENV.MAX_VUS || 2000),
      stages: [
        { target: base, duration: '1m' },
        { target: spike, duration: '10s' },
        { target: spike, duration: '1m' },
        { target: base, duration: '10s' },
        { target: base, duration: '2m' },
      ],
    },
  },
  // During the spike itself some slowness is expected; errors are not
  thresholds: { ...thresholds, http_req_duration: ['p(95)<1500'] },
};

export function setup() {
  return seed(6, 10, 50);
}

export default function (data) {
  visit(data);
}
