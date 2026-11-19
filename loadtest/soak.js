// Soak test: steady, moderate load for a long time (default 1 hour). Finds what only shows up over time:
// memory leaks, connection pool exhaustion, growing queues, caches that never evict, slowly filling disks.
//   k6 run -e RPS=50 -e DURATION=1h loadtest/soak.js
import { seed, visit, thresholds } from './lib/itube.js';

export const options = {
  // Keep each virtual user's session cookie between iterations (k6 clears cookies after every iteration by default)
  noCookiesReset: true,
  scenarios: {
    soak: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RPS || 50),
      timeUnit: '1s',
      duration: __ENV.DURATION || '1h',
      preAllocatedVUs: 100,
      maxVUs: 500,
    },
  },
  thresholds,
};

export function setup() {
  return seed(6, 10, 50);
}

export default function (data) {
  visit(data);
}
