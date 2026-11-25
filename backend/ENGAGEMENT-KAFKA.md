# Kafka for History, Likes, Saved Videos and Playlists

The production write path is asynchronous. Reads still use the existing ScyllaDB stores and obtain video
metadata from catalog-service/PostgreSQL. No JPA/H2 database, extra microservice, CDN change or new page cache
is introduced.

```text
Player or library action
  -> existing authenticated controller
  -> validate + reserve Idempotency-Key in ScyllaDB
  -> publish command to Kafka, wait for broker acknowledgement
  -> HTTP 202 Accepted + operationId + Location header

Kafka consumer in engagement-service
  -> load durable inbox receipt
  -> validate current ownership/visibility/version and persist an immutable mutation plan
  -> apply the plan to the existing engagement tables
  -> persist SUCCEEDED or FAILED

Frontend polls the authenticated operation endpoint
  -> show success/reload the list only after SUCCEEDED
```

For a beginner: the **producer** submits the work, Kafka keeps the ordered messages, and the **consumer**
performs the database update. `202` means Kafka accepted the work; it does not mean the update already finished.
The operation receipt lets the frontend check that second step without guessing how long the consumer needs.

## Actions covered

| Feature | Commands |
| --- | --- |
| History | Record actual playback, remove one entry, clear history |
| Likes | Like, unlike |
| Saved videos | Save, remove, clear Watch later |
| Manual playlists | Create, rename, delete, add/remove video, move video |
| Automatic playlists | Explicit Refresh recommendations |

The existing automatic-playlist GET still resolves/generates a cached mix on demand. Its normal cache fill is
not a queued user edit. Snapshot playback and every other GET keep their existing read behavior.

## API contract

All listed mutation routes keep their paths but now return **202** with this shape:

```json
{
  "operationId": "b1a5ea30-041d-4d19-beb0-f48cb78279a9",
  "state": "PENDING",
  "result": null,
  "httpStatus": 0,
  "message": null
}
```

`Location: /api/engagement/operations/{operationId}` and `Retry-After: 1` accompany the response.
Send a unique version-4 UUID in **Idempotency-Key** for each logical action. Reuse that exact key when retrying
an uncertain request. Reusing a reserved key for a different action is rejected with 409, including concurrent
requests. Clients without a key receive a generated one but cannot safely retry a lost response without it.

`GET /api/engagement/operations/{id}` returns:

- **PENDING:** reserved/sending/queued/processing; keep waiting.
- **SUCCEEDED:** database writes finished; `result` contains the original History/Playlist response, or null
  for a mutation without a result. History's mutation result contains the stable entry id/time; the normal
  History GET resolves current video metadata.
- **FAILED:** validation rejected the command; `httpStatus` and `message` explain why, for example a playlist
  version conflict. The status endpoint itself returns 200 for an authenticated status check.

The user id always comes from the verified access token. Another user's receipt is never returned. Unknown
operation ids appear as PENDING to avoid disclosing ownership; PENDING alone is not proof of broker acceptance.
Only a successful 202 response confirms acceptance. An unavailable broker returns 503; a timeout has an uncertain
outcome, so retry with the same key. Input/ownership checks may reject a request before publishing as usual.

The inbox reservation requires ScyllaDB to be available when accepting a request. Kafka buffers consumer
backlogs/restarts; this design does not promise API write availability during a total ScyllaDB outage.
There is no business-data/Kafka dual write: API submission reserves an inbox record, and only the consumer
updates library data. If the process dies between reservation and send, the request has not been acknowledged
as accepted; retrying its key republishes the original envelope.

## Ordering, retries and duplicate protection

- Topic: **engagement.commands.v1**, configurable with `ENGAGEMENT_KAFKA_TOPIC`. All four features share this
  topic and use userId as the key. This preserves Kafka append order for a user's edits and clears. Concurrent
  requests are ordered by the broker, not by wall-clock click time.
- Consumer group: **itube-engagement-commands-v1**. Three consumer threads per pod by default; configure with
  `ENGAGEMENT_KAFKA_CONCURRENCY`. Total useful parallelism is bounded by topic partitions.
- Offsets are committed after successful processing of each record. Auto-commit is disabled.
- An inbox receipt and an immutable plan are stored before writes. Retries retain the original History id,
  item timestamp, index key, playlist version, playback order and result. Receipts have no TTL, preventing a
  replayed old add/create from restoring data after a remove/clear/delete.
- History/Likes/Saved projections use logged batches and persisted, monotonic per-user CQL timestamps.
  Redelivery repairs all indexes after partial writes. Old retries cannot overwrite a newer tombstone.
- Playlists retain conditional version updates. A deleted playlist leaves a minimal id/version marker with
  its title and videos erased, so an in-flight duplicate create cannot bring it back.
- Infrastructure/application failures retry **in place every second**. Later commands cannot overtake a failed
  database write. This can delay other users sharing that consumer's partitions; investigate persistent lag.
- Business validation failures become FAILED receipts and allow processing to continue. Only invalid envelopes,
  unsupported schema, invalid user keys or conflicting event identities go to **engagement.commands.v1-dlt**.
  A failed DLT publish is itself retried; it is not silently acknowledged.

This is at-least-once processing with retry-safe database effects, not a distributed exactly-once transaction
between Kafka and ScyllaDB. Do not expire receipts/clocks/deletion markers or arbitrarily increase this topic's
partition count while old records can still be processed. A partition-count migration needs a drained/controlled
cutover to avoid moving a user's messages to a different partition mid-stream.

## Frontend behavior

`frontend/src/api/engagement.js` adds operation keys, polls with a real 15-second network deadline, and adapts
completed results to the existing components. Buttons remain busy until confirmation. A delayed consumer shows
an honest pending message; refreshing the page checks current library state. A network retry reuses its key.
The player retains one key for a playback attempt across failures, pause/resume and quality changes.

A History read immediately after playback waits for the outstanding watch write in that tab. Account changes
detach previous-account operations and stop their polling. Other tabs/devices can briefly see older database
state until their writes are consumed; there is no WebSocket push in this implementation.

## Local Docker and cloud deployment

- Docker Compose now stores broker logs in the named **kafka_data** volume.
- `tf_docker/main.tf` already has Kafka persistent storage, and `deploy/k8s/kafka.yaml` already has an EBS-backed
  persistent volume. The existing cloud bootstrap address remains `kafka:9092`.
- Spring Kafka admin declares the command/DLT topics at service startup. Commands retain up to 7 days and
  DLT records up to 14 days, **or 64 MiB per partition**, whichever limit removes records first. Segments roll
  at 16 MiB or one hour, keeping the small development disk's active segments bounded.
- Existing producer settings use `acks=all` and idempotence. The new topics require two in-sync replicas when
  configured with at least two replicas; the current single-broker lab uses one replica/minimum ISR of one.
- The current cloud broker is still single-node: persistent storage does not make it highly available. A
  production HA rollout needs multiple brokers/replicas (or a managed Kafka service), sufficient disk, and
  monitoring. This change does not deploy cloud resources or claim that the live cluster was updated.
- Deploy the matching backend, frontend and gateway together. The gateway now routes the operation endpoint.

Monitor consumer lag/oldest unprocessed age against both retention limits, broker disk, DLT records and
`itube.engagement.commands` counters (action/outcome tags), plus `itube.engagement.command.failures` and
`itube.engagement.command.deadletters`. Prometheus exposes the usual normalized names.
Do not reset offsets or replay corrected DLT messages blindly. Inspect the envelope and receipt; rejected
business operations should be resubmitted through the authenticated API with a fresh key after correction.

## Verify

From the repository root:

```powershell
docker compose up -d kafka scylla
```

From `backend`:

```powershell
.\mvnw.cmd -pl engagement-service,e2e-tests -am test '-Ditube.test.kafka=true' '-Ditube.test.scylla=true'
```

The opt-in test uses real Docker Kafka/ScyllaDB, a unique test keyspace and unique topics, and deletes only its
own test data. Catalog is an explicit fake; video metadata storage remains PostgreSQL through catalog-service.
Normal tests use memory stores and an explicitly selected inline test transport under the historical `h2`
profile. That profile is a test label here, not an H2 database configuration or a production Kafka fallback.

From `frontend`: `npm test -- --run --maxWorkers=2` and `npm run build`.

Implementation follows the external-database delivery limitations in the [Kafka design documentation](https://kafka.apache.org/40/design/design/)
and Spring's [ordered retry/dead-letter handling](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html).
