# Automatic personalized playlists

Open **Playlists → Made for you**. ITube generates a **For you** mix without creating a manual playlist.
Use **Play your mix**, **View your mix**, or **Refresh recommendations**. Generated mixes are read-only;
your manually created playlists are separate and keep their existing editing behavior.

Explicit refreshes now run through Kafka and return `202 Accepted`; the UI waits for the saved snapshot.
See [ENGAGEMENT-KAFKA.md](ENGAGEMENT-KAFKA.md) for the operation-status contract and retry behavior.

## How recommendations work

This is a deterministic content-based recommender, not an external AI service or a trained model.
It uses only the signed-in user's own engagement:

- The latest **200 watch-history entries** and **100 likes**, read from ScyllaDB.
- Channel affinity, title topics and typical video length inferred from usable public video metadata.
  Video length means the video's duration; ITube does not currently record watch completion percentages.
- Likes receive more weight than a single watch. Older signals decay; repeated watches of one video
  contribute at most three watch weights, preventing repeated playback from dominating a profile.
- Catalog-service queries PostgreSQL for up to **300 candidates**, reserving some candidate space for
  preferred channels and using recent public uploads for discovery.
- Ranking combines channel/topic similarity, usual duration and freshness. A diversity pass favors
  different channels before filling remaining spaces. Stable score/id tie-breaking preserves order.

Each mix contains at most **30 distinct videos**. Ids found in the sampled history/likes are excluded.
Older watches or likes outside these bounded windows can reappear. New accounts get recent eligible
public videos, with an honest empty state if the catalog has none.

## Visibility and availability

Candidates must be **PUBLIC**, **READY**, and have a nonblank source storage key in PostgreSQL. Private,
unlisted, members-only, processing, missing-source and deleted videos are excluded from automatic discovery.
This does not change what users may add to manual playlists.

Every mix/snapshot response revalidates ids using fresh catalog queries, bypassing cached video summaries.
A newly private/deleted/unready video is omitted from the returned queue, including old playback snapshots.
Normal playback authorization still applies. Availability is based on catalog state; a subsequent storage or
network failure can still affect playback. Catalog outages return a retryable 503 rather than stale metadata.

## Updating without reshuffling playback

The current mix has a bounded 60-second in-process cache keyed by user **and the sampled engagement records**.
A watch, like, unlike, or history deletion/clear changes that key, so the next visit reflects the new signals.
New catalog uploads enter on cache expiry or immediately through **Refresh recommendations**.

Each generation saves an ordered playback snapshot in ScyllaDB, under `(user_id, snapshot_id)` with a
**24-hour TTL**. Existing queues keep their order while future mixes can change. Snapshots cannot be read by
another account and cannot be renamed/reordered/deleted through the manual playlist API. Expired links direct
the user back to Playlists for a fresh mix. Requests are cancelled on account/navigation changes in the UI.

## Architecture and files

```
React Playlists / AutomaticPlaylist card
  → engagement-service: AutomaticPlaylistController → AutomaticPlaylistService
      → WatchHistoryStore + LikeStore + AutomaticPlaylistStore (ScyllaDB)
      → CatalogClient (seed metadata)
      → RecommendationCatalogClient (bounded candidates + fresh eligibility)
          → catalog-service: RecommendationCatalogService → VideoRepository (PostgreSQL shards)
```

`RecommendationRanker` contains the scoring and diversification rules. `ScyllaAutomaticPlaylistStore`
persists snapshot ids/order; `InMemoryAutomaticPlaylistStore` supports isolated tests. The existing schema
initializer adds `automatic_playlist_snapshots` when schema creation is enabled.

The new internal catalog calls use the existing internal-token guard, tracing, timeouts, circuit breaker,
retry and shard routing. Engagement-service never connects directly to catalog tables. Candidate/metadata
batches and caches are bounded; snapshot TTL bounds persisted derived data. No new infrastructure or external
model credentials are required.

## API

| Method | Path | Behavior |
| --- | --- | --- |
| GET | `/api/playlists/automatic` | Generate/reuse the current owner's mix |
| POST | `/api/playlists/automatic/refresh` | Force a new candidate/ranking pass |
| GET | `/api/playlists/automatic/{snapshotId}` | Read and revalidate an existing ordered snapshot |

These endpoints require a session and send `Cache-Control: no-store`. The response uses the existing
`playlist`/`videos` playback envelope plus `personalized`, `description` and `generatedAt`.
Automatic frontend ids use `automatic:{snapshotId}` to keep manual ids separate. URLs are
`/playlists/automatic/{snapshotId}` and `/watch/{videoId}?playlist=automatic%3A{snapshotId}`.

Catalog's protected APIs are `POST /internal/recommendations/candidates` and
`POST /internal/recommendations/videos`. The public gateway blocks `/internal/*`.

## Verification

- `AutomaticPlaylistTests`: ranking, recency, duration/topics, duplicates, cold start, signal updates,
  privacy revalidation, snapshot ownership, 401/503 handling, and stable playback order.
- `RecommendationCatalogTests`: filters and shard routing against normal H2 test shards; also supports real
  PostgreSQL URLs through `-Ditube.test.pg.shard0=...` and `-Ditube.test.pg.shard1=...`. Use isolated test databases.
- `AutomaticPlaylistScyllaTests`: persisted ordered ids, owner isolation and server-side TTL, enabled by
  `-Ditube.test.scylla=true` alongside the other real ScyllaDB checks.
- Frontend tests cover automatic generation, refresh, read-only detail, account changes, direct links and
  player queue advancement. Run `npm test -- --run --maxWorkers=2` on machines also running the full stack.

Build affected services with `./mvnw -pl catalog-service,engagement-service -am -DskipTests package`, then restart
both services. The gateway routes all `/api/playlists/*` calls to engagement-service. Saved Videos and manual
Playlists routes are also included; rebuild an older gateway with
`docker compose --env-file backend/.env up -d --build gateway` from the repository root.
