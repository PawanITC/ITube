# Playlists

Playlists belongs to `engagement-service`, alongside History, Likes and Saved Videos. Each signed-in user
can create multiple **private** named playlists. A playlist holds up to **500 unique videos** in a saved order.

The library also includes an automatically generated **For you** mix. See
[AUTOMATIC-PLAYLISTS.md](AUTOMATIC-PLAYLISTS.md) for its ranking, eligibility and playback snapshot behavior.

## Try it

1. Open a video and click **Add to playlist**. Select an existing playlist, or enter a name and click
   **Create and add**. Saving the same video again does not create a duplicate.
2. Open **Playlists** from the sidebar or mobile navigation. Create or open a playlist.
3. Rename it, move videos up/down, remove entries, or delete the playlist after confirming.
   Removing a playlist or an entry never deletes the video itself.
4. Click a thumbnail or **Play playlist**. The existing player shows the queue and Previous/Next controls,
   and advances when a video ends. Unavailable entries are skipped. Members-only playback still requires
   the existing membership checks.
5. Playlist URLs are `/playlists` and `/playlists/{id}`. Playback keeps `?playlist={id}` in the URL, so
   reloading also works for unlisted videos absent from the public feed.

Rebuild/restart engagement-service and the gateway to load the new API route and schema. The configured
ScyllaDB schema initializer creates `playlists_by_user` when `itube.scylla.create-schema=true`.
To rebuild just the gateway locally, run `docker compose --env-file backend/.env up -d --build gateway`
from the repo root. Use the project's normal development startup for the application services.

## API

Every write below now returns **202 Accepted** with an operation receipt. Completed metadata is in the
receipt's `result`. See [ENGAGEMENT-KAFKA.md](ENGAGEMENT-KAFKA.md) for the shared asynchronous contract.

All endpoints use the current session user. Another user's playlist returns 404. No owner id is accepted
from request bodies. List and detail endpoints accept `page` (starting at zero) and `size` (1–100, default 20).

| Method | Path | Body / result |
| --- | --- | --- |
| POST | `/api/playlists` | `{ "title": "Learning Java" }` → 202, operation receipt and status Location |
| GET | `/api/playlists` | Newest-created playlists first |
| GET | `/api/playlists/{id}` | Metadata and a page of ordered videos |
| PATCH | `/api/playlists/{id}` | `{ "title": "Spring Boot" }` → updated metadata |
| DELETE | `/api/playlists/{id}` | Queue playlist deletion → 202 |
| PUT | `/api/playlists/{id}/videos/{videoId}` | Append if absent → metadata |
| DELETE | `/api/playlists/{id}/videos/{videoId}` | Remove if present → metadata |
| PUT | `/api/playlists/{id}/videos/{videoId}/position` | `{ "position": 0, "version": "<version from detail>" }` → metadata |

Detail responses contain `playlist`, `videos`, `page`, `size`, and `hasMore`. Each video entry contains
`videoId`, `addedAt`, zero-based `position`, and the existing `VideoSummaryDto` as `video`.
A deleted/private video, or an unavailable catalog-service, produces a null `video` without leaking its
title or thumbnail. The owner can still rename/delete the playlist or move/remove the unavailable entry.
Adding requires a visible video; unlisted videos and the owner's own private videos are allowed.

## How the code works

The write flow is **React → PlaylistController → CommandGateway → Kafka → CommandProcessor → PlaylistService → PlaylistStore → ScyllaDB**.

- `PlaylistController` validates HTTP input and reads the account id from the session.
- `PlaylistService` checks ownership/visibility, adds timestamps, prevents duplicates, applies moves,
  and asks `CatalogClient` for current video details in a batch.
- `PlaylistStore` defines persistence operations. Its `Playlist` and `Item` records hold stored data.
- `ScyllaPlaylistStore` stores the title, ordered ids and added dates in one row, partitioned by owner.
  It uses conditional writes instead of separate lookup/index writes, so a playlist edit is atomic.
- `InMemoryPlaylistStore` follows the same rules for isolated tests; production uses ScyllaDB.
- DTO records describe HTTP data: `PlaylistRequest` for names, `PlaylistPositionRequest` for moves,
  `PlaylistResponse` for metadata, `PlaylistVideoResponse` for entries, and `PlaylistDetailResponse` for a page.
- `Playlists.jsx` manages the library/detail screens; `AddToPlaylistButton.jsx` adds from the player;
  `usePlaylistQueue.js` and `PlaylistQueue.jsx` provide ordered playback through `WatchPage`.

The `version` is a new UUID whenever a playlist changes. A move includes the version the user saw.
If someone edits the same playlist first, validation returns **409**, or an already queued command gets a
FAILED receipt with status 409. The UI then asks for a refresh. The consumer persists the planned version and
checks it again when applying the change, preserving concurrent edits without replaying a move twice.
The 500-video bound keeps each atomic update and the player queue small. There is no new JPA entity or
PostgreSQL table, and no separate microservice is needed for Playlists.

## Verify

From `backend`: `./mvnw -pl engagement-service -am test`.
From `frontend`: `npm test -- --run` and `npm run build`.

For real ScyllaDB checks, start the compose `scylla` service and add `-Ditube.test.scylla=true` to the Maven
command. `PlaylistScyllaTests` uses a uniquely named test keyspace, verifies persisted order, concurrent
conditional edits and deletion, then drops only that test keyspace.
