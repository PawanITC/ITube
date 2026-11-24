# Saved videos / Watch later

Saved Videos belongs to `engagement-service`, alongside History and Likes. The player has a Save button;
the sidebar and mobile navigation open **Watch later** (`/saved-videos`, also `/watch-later`).
Watching a saved video does not remove it. Click its thumbnail to replay it through the existing player.

## API

Writes now use Kafka and return **202 Accepted** with an operation receipt. See
[ENGAGEMENT-KAFKA.md](ENGAGEMENT-KAFKA.md) for idempotency keys, completion polling, retries and tests.

Every endpoint uses the signed-in session. Clients never supply an account id.

| Method | Path | Result |
| --- | --- | --- |
| PUT | `/api/saved-videos/{videoId}` | Queue save, 202; repeated saves keep the original date |
| GET | `/api/saved-videos/{videoId}` | `{ "videoId": 25, "saved": true }` |
| GET | `/api/saved-videos?page=0&size=20` | Newest saved first; size 1–100 |
| DELETE | `/api/saved-videos/{videoId}` | Queue removal, 202, even if already absent |
| DELETE | `/api/saved-videos` | Queue clear for this user, 202 |

List entries contain `videoId`, `savedAt`, and the existing `VideoSummaryDto` as `video`.
Missing/private videos (or a catalog outage) produce a null `video`: the UI shows a removable unavailable
entry without leaking its title or thumbnail. Saving a missing video or another owner's private video
returns 404. Unlisted and members-only videos follow catalog-service's existing playback rules.

## How the code works

1. `SavedVideoController` validates HTTP input and takes the user id from the verified access token.
2. `CommandGateway` validates using `SavedVideoService`, reserves an operation key, and publishes to Kafka.
   The consumer persists a mutation plan and applies it through `ScyllaProjectionWriter` before confirming success.
   `SavedVideoService` still supplies status/list reads and current video details through `CatalogClient`.
3. `SavedVideoStore` is the storage interface, like `WatchHistoryStore` and `LikeStore`.
4. `ScyllaSavedVideoStore` reads the unique user/video lookup and newest-first user index in ScyllaDB.
   `saved_video_lookup` and `saved_videos_by_user` are added by the existing idempotent schema script at
   startup when `itube.scylla.create-schema=true`. There is no new PostgreSQL/JPA entity for this feature.
5. `SavedVideoResponse` and `SavedVideoStatusResponse` are records for the list and button state.
6. `api/savedVideos.js`, `SaveButton.jsx`, and `SavedVideos.jsx` connect the player and library to the API.
   Requests use session cookies; buttons guard duplicate submissions and report failures without claiming success.

The `h2` profile uses `InMemorySavedVideoStore` for isolated tests. Production uses the configured ScyllaDB
session; PostgreSQL video metadata is reached through catalog-service. The gateway routes `/api/saved-videos`
to engagement-service. Existing History/Like behavior and caches are reused without adding another page cache.

## Verify

From `backend`: `./mvnw -pl engagement-service -am test`.
From `frontend`: `npm test -- --run` and `npm run build`.

For the optional real-database test, start `docker compose --env-file backend/.env up -d scylla` at the repo root,
then run `./mvnw -pl engagement-service -am test -Ditube.test.scylla=true` from `backend`.
This creates and drops only a uniquely named test keyspace and checks persistence, ordering, duplicate saves,
removal, and clearing one user's list without changing another user's saved videos.
