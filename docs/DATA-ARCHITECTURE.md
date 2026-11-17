# Data architecture

ITube's data is split by domain so each part can scale on its own. Every domain owns its data, and no
database query or foreign key crosses from one domain to another. Domains refer to each other by id only.

| Domain | Store | What it holds | How it scales |
|---|---|---|---|
| **identity** | PostgreSQL schema `identity` | `accounts` | One primary + read replicas; small rows, mostly reads |
| **catalog** | PostgreSQL schema `catalog` | `channel`, `subscriptions`, `video` (metadata + storage keys), `video_rendition` (keys) | Read replicas + caching; heavy reads, few writes |
| **payments** | PostgreSQL schema `payments` | Stripe customers, tiers, memberships, payments, refunds, payouts, webhook inbox, outbox | Single primary, strongly consistent; low volume. Don't shard |
| **engagement** | ScyllaDB keyspace `itube_engagement` | watch history, likes, comments | Add nodes: every read is one partition, writes spread by user/video id |
| **media** | Object storage (S3; SeaweedFS locally) | video sources, 360p/720p renditions, thumbnails | Effectively unlimited; served by pre-signed URL (CDN in production) |

Sessions and rate limits stay in Redis. Kafka carries small events only (ids and storage keys).

## Where things are defined

- PostgreSQL: `backend/src/main/resources/schema-migrate.sql` (creates the schemas, moves tables from the
  old single `public` schema, drops cross-domain foreign keys), then `schema-identity.sql`,
  `schema-catalog.sql`, `schema-payments.sql`. All run on every start and are idempotent.
- ScyllaDB: `backend/src/main/resources/scylla/engagement.cql`, applied on startup when
  `itube.scylla.create-schema=true`. One table per query:
  - `watch_history_by_user` partition `user_id`, newest first by `entry_id` (a timeuuid, also the public id)
  - `likes_by_user` (list), `like_lookup` (is it liked; written with lightweight transactions), `likes_by_video` (cleanup)
  - `comments_by_video` partition `video_id`, newest first; the author's name is copied in when posted
- Object storage keys: `videos/{random uuid}/source`, `.../thumbnail-{uuid}`, `.../renditions/{360p|720p}.mp4`
  (`ITubeBackend.storage.MediaKeys`).

## How cross-domain features work without joins

- **History / liked videos:** read ids from ScyllaDB, then fetch the video summaries for that page in one
  batched catalog query (`VideoRepository.findVisibleVideoSummaries`). Videos that are gone or private are
  skipped (likes) or shown without details (history).
- **Comments:** the author's display name is stored with the comment, so reading comments never touches identity.
- **Deleting a video:** the catalog row is deleted, then `video.deleted` goes to Kafka with every storage key.
  `itube-media-cleanup` deletes the files; `itube-engagement-cleanup` deletes the likes and comments.
  Watch history is kept (it's the viewer's record). A lost message only leaves garbage behind, because
  every reader already ignores ids whose video no longer exists.
- **Uploads:** the file streams to object storage first, then the video row is inserted in a short
  transaction. A failed insert deletes the uploaded files.
- **Playback:** `/api/videos/{id}/file`, `/renditions/{q}` and `/thumbnail` check access as before, then
  redirect to a pre-signed URL. Set `itube.storage.redirect-playback=false` to stream through the backend
  instead (used by the local Kubernetes overlay `deploy/k8s-local`, where browsers can't reach SeaweedFS).

## Moving a domain to its own database

Nothing in the code joins across schemas, so moving a domain is configuration plus a data copy. The
exception is account registration, which creates the account (identity) and its first channel (catalog)
in one transaction. Once those are separate databases, that step needs an event (`account.created`)
or a retryable two-step write.

## Upgrading an existing database

Nothing to do by hand. On the first start of this version:

1. `schema-migrate.sql` moves the old `public` tables into `identity`, `catalog`, `payments`, and moves
   `watch_history`, `liked_video`, `video_comment` into `legacy_engagement`.
2. `LegacyDataMigration` (on by default; `itube.migration.legacy-data`) copies video, thumbnail and
   rendition bytes to object storage and empties those columns, then copies the legacy engagement tables
   to ScyllaDB and renames them `*_copied`. It's safe to run on several instances at once and to re-run.
3. When every environment has migrated, drop `legacy_engagement.*_copied` and the unused byte columns
   (`catalog.video.video`, `catalog.video.thumbnail`, `catalog.video_rendition.content`).

## API changes for the frontend

- History entry `id` and comment `commentId` are now UUID strings (were numbers).
- `GET /api/videos/{id}/comments` returns the newest 100 by default (`?limit=` up to 500).
- History and liked-videos `page` parameters still work, but the cost of a page grows with its offset.
  For deep scrolling, switch to a cursor (`before=<last id>`).

## Not done yet (next scaling steps)

- Direct-to-storage uploads from the browser (pre-signed PUT, multipart for files over 5 GB).
- HLS/DASH segments and a CDN with signed cookies instead of whole MP4s.
- Cursor pagination for history, likes and comments; time-bucketed comment partitions for very popular videos.
- Counters (subscribers, likes, views) kept asynchronously instead of `COUNT(*)`.
- Production ScyllaDB: three or more nodes with replication factor 3 (e.g. Scylla Operator).
