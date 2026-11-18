// Smoke test: a little traffic for 30 s, to check the stack works end to end before a real load test.
//   k6 run loadtest/smoke.js            (BASE_URL defaults to http://localhost:8080, the gateway)
import { seed, visit, thresholds } from './lib/itube.js';

export const options = {
  // Keep each virtual user's session cookie between iterations (k6 clears cookies after every iteration by default)
  noCookiesReset: true,
  scenarios: {
    smoke: { executor: 'constant-vus', vus: 5, duration: __ENV.DURATION || '30s' },
  },
  thresholds,
};

export function setup() {
  return seed(2, 5, 5);
}

export default function (data) {
  visit(data);
}
