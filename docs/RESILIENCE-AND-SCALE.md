# Resilience and scale

Two questions this page answers:

1. **What happens when something breaks?** Pods crash, nodes disappear, a database is slow, a service is down, Kafka
   hiccups. With hundreds of millions of users, something is always broken somewhere.
2. **What would it take to serve 100 million people?** What was measured, how to measure it properly, and the
   estimate.

## Part 1: patterns, and where they are in the code

### Between services (every HTTP call to another ITube service)

[Downstream](../backend/common/src/main/java/ITubeBackend/common/client/Downstream.java) wraps every call in four
Resilience4j patterns (settings: `itube.resilience.*` in [itube-defaults.properties](../backend/common/src/main/resources/itube-defaults.properties)):

| Pattern | Setting | What it prevents |
|---|---|---|
| **Timeouts** | connect 1 s, read 2 s | A slow dependency holding our threads forever |
| **Retry with exponential backoff + jitter** | 3 attempts, 100 ms, 200 ms... ±50% | Failing a user request because of one dropped connection or a pod being replaced. Only I/O errors and 5xx are retried, never 4xx |
| **Circuit breaker** | opens when ≥50% of the last 20 calls failed or took >2 s; half-opens after 10 s | Hammering a struggling service (which keeps it down) and making every caller wait for timeouts. Open = fail in microseconds |
| **Bulkhead** | max 100 concurrent calls per downstream | One slow dependency using up every request thread of the caller |

Their state is exported as metrics (`resilience4j.circuitbreaker.state`, `...calls`, `resilience4j.bulkhead.*`).

### Graceful degradation: what each outage costs

Callers decide what an outage means for the user ("fail open" or "fail closed"):

| If this is down... | ...these keep working | ...these don't | Tested in |
|---|---|---|---|
| catalog-service | sign-in (without channel details), registration (channel created later via Kafka), History (without titles), recording views, unliking | browsing, playing, liking | identity + engagement BDD |
| engagement-service | browsing, watching, uploading, subscribing | history, likes, comments | chaos test below |
| payments-service | everything except members-only videos, which **fail closed** (503, never free) | members-only playback, memberships | catalog BDD |
| identity-service | everyone already signed in (the session is in Redis) | new sign-ins | (design) |
| one catalog shard | the home feed and search (built from the other shards), everything on the other shards | that shard's creators' pages | (design; `itube.catalog.scatter.partial`) |
| transcode-worker | everything; new videos stay PROCESSING and are transcoded when it's back | - | sweeper |
| Kafka | everything users do synchronously; events are retried, transcodes re-sent by the sweeper | - | (design) |

### Inside each service

- **Load shedding** ([LoadShedding](../backend/common/src/main/java/ITubeBackend/common/web/LoadShedding.java)): at
  most 200 requests in flight per pod (32 for identity, whose BCrypt is deliberately slow). Extra requests get an
  immediate 503 + `Retry-After`. Measured effect below.
- **Virtual threads** (Java 21): a request waiting for a database or another service doesn't hold an OS thread.
- **Fail-fast connection pools**: a request waits at most 2 s for a database connection.
- **No database connection held during slow work**: uploads go to object storage before the short insert
  transaction, BCrypt runs before the registration transaction, remote calls happen outside transactions.
- **Caffeine caches** with per-entry TTLs; one load per key even under a stampede.

### Kafka (asynchronous work)

[KafkaResilienceConfig](../backend/common/src/main/java/ITubeBackend/common/kafka/KafkaResilienceConfig.java):

- failed messages are retried in place with exponential backoff (500 ms -> 10 s, 5 attempts);
- then parked on `<topic>-dlt` (dead-letter topic), so one bad message never blocks the ones behind it;
- malformed messages go straight to the DLT;
- producers are idempotent (`enable.idempotence`, `acks=all`), and give up after 5 s instead of blocking a request;
- every consumer is idempotent, so redelivery and DLT replay are safe;
- **self-healing**: [TranscodeSweeper](../backend/catalog-service/src/main/java/ITubeBackend/service/video/TranscodeSweeper.java)
  re-queues videos stuck in PROCESSING (up to 5 times, then FAILED); an unreadable video is reported FAILED at once
  instead of being retried forever.

### At the edge

- **Gateway** ([template](../gateway/templates/default.conf.template)): per-IP rate limits, 2 s connect timeout, one
  retry on another pod for safe requests (never POST), keep-alive pools.
- **Cloudflare**: DDoS protection, edge rate limit on sign-in, cache in front of everything that's the same for
  everyone ([CDN-AND-DNS.md](CDN-AND-DNS.md)).

### In Kubernetes ([deploy/k8s](../deploy/k8s))

- **2+ replicas** of every user-facing service, spread across nodes and zones (`topologySpreadConstraints`).
- **HorizontalPodAutoscaler** per service (CPU 70%): fast scale-up, slow scale-down.
- **PodDisruptionBudget**: node upgrades/drains take at most one replica of a service at a time.
- **Zero-downtime deploys**: `maxUnavailable: 0`, readiness probes, a `preStop` pause so the Service stops sending
  traffic before shutdown, and graceful shutdown that finishes in-flight requests.
- **Liveness ≠ readiness**: a pod whose database is down goes *unready* (no traffic) but isn't restarted.

## Part 2: what was measured

All on one Windows laptop (12 threads, 16 GB): k6, the four services (deliberately small: 320 MB heap, C1 compiler
only), the gateway, Postgres x2, ScyllaDB, Kafka, Redis, SeaweedFS and Grafana. Everything competes for the same CPU,
so these numbers are **far below** what the same code does on dedicated servers. They are useful for comparing, and
for finding where it breaks. Scripts and how to run them: [loadtest/README.md](../loadtest/README.md).

### 1. Clean capacity: 120 user visits/s (peak ≈ 360 API requests/s)

| Metric | Result |
|---|---|
| Errors | **0** of 46,000 requests |
| p95 / p99 latency | **43 ms / 69 ms** |
| Home feed p95 | 41 ms |
| Playback authorisation p95 | 30 ms |

### 2. Overload: 300 visits/s, before and after load shedding

| | Without load shedding | With load shedding |
|---|---|---|
| Requests/s handled | 400 | **600** |
| Successful requests/s | ~276 | **~390** |
| p95 latency | 4.34 s | **0.47 s** |
| Slowest request | 44.9 s | **3.4 s** |
| Iterations k6 had to drop | 10,643 | **1,097** |

Without load shedding every request slowed down together until they timed out (one laptop CPU shared by 1,000
simulated users, four JVMs and BCrypt). With it, the excess is refused in microseconds, and the requests that are
accepted stay fast, so *more* users succeed. In a cluster the gateway retries the refused GETs on another pod and the
autoscaler adds pods.

### 3. Chaos: engagement-service killed under load

60 visits/s for two minutes; engagement-service killed for ~60 s in the middle, then restarted:

| Calls | Success during the outage |
|---|---|
| Home feed, channel pages, thumbnails, playback, video files, subscriptions | **100%** (feed p95 stayed at 24 ms) |
| History, likes, comments | failed while it was down, recovered on their own after the restart |

That is the point of splitting services: an outage of one feature doesn't become an outage of the site.

### Not yet measured (do these in AWS)

- **Real capacity per pod.** Run the load test against the EKS cluster with
  [k6-operator](https://github.com/grafana/k6-operator) (k6 inside the cluster, several runners, so the generator isn't
  the bottleneck), watch the HPAs scale, and note requests/s per vCPU for each service.
- **Spike** (`spike.js`) to check the autoscaler reacts in time, and **soak** (`soak.js`, 1-24 h) for leaks.
- **Chaos in the cluster**: `kubectl delete pod`, drain a node, stop a shard's database, block Kafka. Tools like
  Chaos Mesh or AWS Fault Injection Service automate this.

## Part 3: what 100 million people would need

A laptop can't generate traffic from 100 million people, and neither can any single machine. Real platforms prove
capacity by load testing one region at a known fraction of target, measuring per-pod throughput, and scaling the
numbers. Here is that estimate with stated assumptions; replace the per-pod figures with what you measure in AWS.

### Assumptions

- 100 million daily active users (DAU), peak hour traffic = 3x the daily average.
- Each user per day: 8 videos watched (40 minutes, 720p ≈ 2.5 Mbit/s), ~60 API calls, 0.2 sign-ins.
- 70% of API calls are the same for everyone (feed, thumbnails, assets) and answered by the CDN.

### Traffic

| | Average | Peak |
|---|---|---|
| API calls at the edge (100M × 60 / 86,400 s) | ~70,000/s | ~210,000/s |
| API calls reaching ITube (30%) | ~21,000/s | ~63,000/s |
| Video bytes (100M × 2,400 s × 2.5 Mbit/s / 86,400 s) | ~7 Tbit/s | ~20 Tbit/s |
| Sign-ins (BCrypt ≈ 0.1 CPU-s each) | ~230/s | ~700/s ≈ 70 vCPU |
| History writes (100M × 8) | ~9,300/s | ~28,000/s |

### What that means

- **Video is a CDN problem, not a server problem.** 20 Tbit/s of video is roughly 75 PB per day. That is only possible
  from CDN edges, which is why the CDN is the first thing to get right.
- **Services**: assume ~500 requests/s per vCPU for this mix (measure it). 63,000 requests/s at peak is about
  130 vCPU, plus ~70 vCPU for sign-ins. With headroom: catalog ~60 pods, engagement ~40, identity ~40 (BCrypt),
  gateway ~20, payments 4, and transcode workers sized by uploads, not viewers. The HPAs already do this; the node
  group (or Karpenter/cluster autoscaler) must be allowed to grow with them.
- **Catalog database**: with the caches, well over 95% of reads never reach Postgres. The reason to shard is size and
  write growth: 1 billion videos at ~2 KB is ~2 TB of rows plus indexes. 8-16 shards, each a primary with a standby in
  another zone and 1-2 read replicas, keep each one small and fast. The id scheme supports up to 4,096 shards.
- **ScyllaDB**: ~28,000 history writes/s at peak, ×3 for replication factor 3 ≈ 85,000 replicated writes/s. A cluster of
  6-9 nodes (8 vCPU each) across 3 zones handles that with room to spare; reads are single-partition.
- **Sessions**: 100M users × ~1 KB is ~100 GB of Redis. Either a Redis cluster (sharded, e.g. 8 × 16 GB with replicas)
  or move to signed tokens (JWT) so sign-in state needs no server memory at all ([MICROSERVICES.md](MICROSERVICES.md)).
- **Kafka**: events are tiny (ids and keys). 3 brokers with replication factor 3; raise partitions on busy topics so
  more consumers can run in parallel.
- **More than one region**: at this scale users are on every continent. Run the read path (gateway, catalog with read
  replicas, engagement with a Scylla datacenter per region) in 2-3 AWS regions and keep writes for a creator in their
  home region. Cloudflare can route each visitor to the nearest healthy region (Load Balancing, paid).

### What is still missing for real production

Honest list, roughly in priority order:

1. Managed, replicated data stores (RDS per shard, 3-node ScyllaDB, MSK or 3 Kafka brokers, ElastiCache) instead of
   single in-cluster pods.
2. Cluster autoscaling (Karpenter) so HPAs can get nodes, and multi-AZ node groups.
3. An observability stack in the cluster (the services already export OpenTelemetry metrics, traces and logs) with
   alerts on the metrics above.
4. HLS/DASH segments instead of whole MP4 renditions, so players can switch quality and the CDN caches small pieces.
5. Cursor pagination for history, likes and comments (offset paging gets slower with depth).
6. Counters (views, likes, subscribers) aggregated through Kafka instead of `COUNT(*)`.
7. JWT instead of server sessions.
