# Load tests (k6)

[k6](https://k6.io) scripts that behave like ITube users: sign in once, then keep visiting - browsing the feed and
channels, watching (playback authorisation, the file redirect, recording History), liking and commenting, and
looking at their own lists. Results and the 100-million-user estimate: [docs/RESILIENCE-AND-SCALE.md](../docs/RESILIENCE-AND-SCALE.md).

| Script | Shape | Use it to |
|---|---|---|
| `smoke.js` | 5 users, 30 s | check the stack works before a real test |
| `load.js` | ramp to `TARGET_RPS` visits/s, hold, ramp down | find the rate the system handles cleanly |
| `spike.js` | `BASE_RPS`, jump to `SPIKE_RPS` for a minute, back | check autoscaling and load shedding under a sudden surge |
| `soak.js` | `RPS` for `DURATION` (default 1 h) | find leaks and slow build-ups |

The scripts use an arrival rate (new visits per second, whether or not the system keeps up), like real users. A
system that can't keep up shows growing latency, errors and `dropped_iterations` instead of hiding it.

Pass/fail thresholds (k6 exits non-zero when missed): under 1% errors, p95 < 500 ms, p99 < 1.5 s, feed and playback
authorisation p95 < 300 ms.

## Running locally

1. Install k6 (`winget install k6` / `brew install k6`) and start the stack (`.\dev.cmd`).
2. One machine sends all requests from one IP, which the gateway rate-limits like an attacker. Raise its limits
   (only for load tests):
   ```bash
   GATEWAY_API_RATE_LIMIT=100000r/s GATEWAY_AUTH_RATE_LIMIT=100000r/m GATEWAY_CONNECTIONS_PER_IP=60000 \
     docker compose up -d gateway
   ```
   (PowerShell: `$env:GATEWAY_API_RATE_LIMIT='100000r/s'` etc., then the same `docker compose` line.)
   `docker compose up -d gateway` without them restores the normal limits.
3. Run:
   ```bash
   k6 run loadtest/smoke.js
   k6 run -e TARGET_RPS=120 -e HOLD=2m loadtest/load.js
   k6 run -e BASE_RPS=50 -e SPIKE_RPS=400 loadtest/spike.js
   ```
   `-e BASE_URL=https://itube.<domain>` points them somewhere else; `-e DEBUG=1` prints every failed request.

Each run first creates its own creators, videos and viewer accounts (`setup()`), so it can run against any
environment, including an empty one.

### Reading the result

- `http_req_failed` and the `✗` checks: what failed, per call type (`feed`, `playback-auth`, `history-add`...).
- `http_req_duration{name:...}`: latency per call type. p95 = 95% of requests were at least this fast.
- `dropped_iterations` > 0: k6 couldn't start visits at the requested rate, because the system (or the machine running
  k6) was too slow.

On a laptop, k6, every service and every database share one CPU, so the numbers are a floor, not a capacity figure.
One early smoke run during development showed failures that couldn't be reproduced afterwards; if you see sudden
failures, run with `-e DEBUG=1` to see the status codes.

## Chaos: killing a service under load

The test in RESILIENCE-AND-SCALE.md: run `load.js` at a steady rate, stop engagement-service (close its window or
kill its process), start it again after a minute. Expected: browse/watch checks stay green, history/like/comment
checks fail only while it's down. In Kubernetes: `kubectl -n itube delete pod -l app.kubernetes.io/name=engagement`
(with two replicas you should see almost no errors at all).

## Running against the EKS cluster

A single laptop can't generate enough load for the cluster. Run k6 inside the cluster with
[k6-operator](https://github.com/grafana/k6-operator) and several parallel runners, pointed at
`http://gateway.itube.svc:8080` (raise the gateway limits there too, or test through Cloudflare from many IPs with
Grafana Cloud k6). Watch `kubectl -n itube get hpa -w` while it runs.
