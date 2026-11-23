# Data architecture

ITube's data is split by domain, and each domain belongs to exactly one service ([MICROSERVICES.md](MICROSERVICES.md)).
Only that service reads or writes it; other services ask it over HTTP (`/internal/**`) or react to its Kafka events.
No database query or foreign key crosses from one domain to another. Domains refer to each other by id only.

| Domain | Owner | Store | What it holds | How it scales |
|---|---|---|---|---|
| **identity** | identity-service | PostgreSQL schema `identity` | `accounts` | One primary + read replicas; small rows, mostly reads |
| **catalog** | catalog-service | PostgreSQL schema `catalog`, **sharded** over several databases | `channel`, `subscriptions`, `video` (metadata + storage keys), `video_rendition`, `upload_session`, `owner_shard` (shard directory) | By creator over shards ([SHARDING.md](SHARDING.md)) + Caffeine caches + CDN |
| **payments** | payments-service | PostgreSQL schema `payments` | Stripe customers, tiers, memberships, payments, refunds, webhook inbox, outbox | Single primary, strongly consistent; low volume. Don't shard |
| **engagement** | engagement-service | ScyllaDB keyspace `itube_engagement` | watch history, likes, comments | Add nodes: every read is one partition, writes spread by user/video id |
| **media** | catalog-service (writes), transcode-worker (renditions) | Object storage (S3; SeaweedFS locally) | video sources, 360p/720p HLS renditions (segments + playlists), thumbnails | Effectively unlimited; served by pre-signed URL or the Cloudflare CDN ([CDN-AND-DNS.md](CDN-AND-DNS.md)) |

Payment rate limits stay in Redis. Who is signed in travels in a signed access token, not a server-side session
([AUTH.md](AUTH.md)); refresh tokens are in the `identity` schema. Kafka carries small events only (ids and
storage keys).

Locally (and in the dev cluster) identity, payments and catalog shard 0 share one PostgreSQL server as separate
schemas; catalog shard 1 is a second server. Each service has its own connection settings
(`IDENTITY_DATABASE_URL`, `PAYMENTS_DATABASE_URL`, `CATALOG_SHARD_0_URL`, `CATALOG_SHARD_1_URL`), so giving a domain
its own database is a configuration change plus a data copy.

## Where things are defined

Each service creates and upgrades its own schema on start (idempotent scripts):

- identity: [schema-identity.sql](../backend/identity-service/src/main/resources/schema-identity.sql)
- payments: [schema-payments.sql](../backend/payments-service/src/main/resources/schema-payments.sql)
- catalog, on every shard: [schema-catalog.sql](../backend/catalog-service/src/main/resources/schema-catalog.sql);
  on shard 0 first [schema-catalog-migrate.sql](../backend/catalog-service/src/main/resources/schema-catalog-migrate.sql)
  (moves tables out of the old `public` schema)
- ScyllaDB: [engagement.cql](../backend/engagement-service/src/main/resources/scylla/engagement.cql), applied on startup
  when `itube.scylla.create-schema=true`. One table per query:
  - `watch_history_by_user` partition `user_id`, newest first by `entry_id` (a timeuuid, also the public id)
  - `likes_by_user` (list), `like_lookup` (is it liked; written with lightweight transactions), `likes_by_video` (cleanup)
  - `comments_by_video` partition `video_id`, newest first; the author's name is copied in when posted
- Object storage keys: `videos/{random uuid}/source`, `.../thumbnail-{uuid}`, `.../hls/{360p|720p}/index.m3u8` with its `init.mp4` and `seg_NNNNN.m4s` segments (videos transcoded before HLS: `.../renditions/{360p|720p}.mp4`)
  ([MediaKeys](../backend/storage/src/main/java/ITubeBackend/storage/MediaKeys.java)). Keys are never reused, so a
  cached copy can't go stale.

## How cross-domain features work without joins

- **History / liked videos:** engagement-service reads ids from ScyllaDB, then asks catalog-service for that page's
  video summaries in one batched call (`POST /internal/videos/summaries`), cached per video for 30 s. Videos that are
  gone or private are skipped (likes) or shown without details (history). If the catalog is down, History still
  loads, without details.
- **Comments:** the author's display name is stored with the comment. It comes from the access token (put there at
  sign-in), so posting a comment doesn't call identity-service.
- **Members-only playback:** catalog-service asks payments-service (`POST /internal/entitlements/decide`) only for
  members-only videos; "allowed" answers are cached for 20 s. Public videos never involve payments.
- **Membership pages:** payments-service shows channel names from catalog-service (cached 60 s), with a placeholder
  if the catalog is down.
- **Registration:** identity-service commits the account, then asks catalog-service to create the first channel; if
  the catalog is down, `account.created` (Kafka) makes it happen later ([MICROSERVICES.md](MICROSERVICES.md)).
- **Deleting a video:** the catalog row is deleted, then `video.deleted` goes to Kafka with every storage key.
  `itube-media-cleanup` (catalog-service) deletes the files; `itube-engagement-cleanup` (engagement-service) deletes
  the likes and comments. Watch history is kept (it's the viewer's record). A lost message only leaves garbage
  behind, because every reader already ignores ids whose video no longer exists.
- **Uploads:** the browser sends the file in parts straight to object storage (resumable, pre-signed URLs); completing
  the upload inserts the video row in a short transaction on the creator's shard and queues transcoding.
- **Transcoding:** `video.transcode` (catalog -> transcode-worker) carries the source key; the worker writes the
  renditions and reports them on `video.transcoded`, which catalog-service records.
- **Playback:** `/api/videos/{id}/file`, `/renditions/{q}`, `/hls/...` (master playlist built from the rendition rows, media playlists rewritten to point every segment back at this API with the viewer's token) and `/thumbnail` check access (cached), then redirect to a
  pre-signed URL, or for public media to the CDN when it's configured. Set `itube.storage.redirect-playback=false` to
  stream through the service instead (used by the local Kubernetes overlay `deploy/k8s-local`, where browsers can't
  reach SeaweedFS).

## Upgrading an existing database

Nothing to do by hand. On the first start of this version:

1. Each service moves its own old `public` tables into its schema (identity, payments, and catalog on shard 0, which
   also moves `watch_history`, `liked_video`, `video_comment` into `legacy_engagement`).
2. catalog-service's `LegacyMediaMigration` copies video, thumbnail and rendition bytes from Postgres to object storage
   and empties those columns; engagement-service's `LegacyEngagementMigration` copies the legacy engagement tables to
   ScyllaDB and renames them `*_copied`. Both are safe to run on several instances at once and to re-run
   (`itube.migration.legacy-data`).
3. catalog-service records every existing creator in the shard directory (logical shard 0) and gives existing videos a
   `created_at`. Existing ids are unchanged.
4. When every environment has migrated, drop `legacy_engagement.*_copied` and the unused byte columns
   (`catalog.video.video`, `catalog.video.thumbnail`, `catalog.video_rendition.content`).

## API changes for the frontend

- History entry `id` and comment `commentId` are UUID strings (were numbers).
- `GET /api/videos/{id}/comments` returns the newest 100 by default (`?limit=` up to 500).
- `GET /api/videos` (home feed) returns the newest 100 by default (`?limit=` up to 200) instead of every video.
- New channel and video ids are large numbers (they carry the shard, see [SHARDING.md](SHARDING.md)), still exact in
  JavaScript. Old ids are unchanged.
- History and liked-videos `page` parameters still work, but the cost of a page grows with its offset.

## Not done yet (next scaling steps)

- HLS/DASH segments instead of whole MP4 renditions.
- Cursor pagination for history, likes and comments; time-bucketed comment partitions for very popular videos.
- Counters (subscribers, likes, views) kept asynchronously instead of `COUNT(*)`.
- Production ScyllaDB: three or more nodes with replication factor 3 (e.g. Scylla Operator); managed Postgres per
  shard with standbys and read replicas.
