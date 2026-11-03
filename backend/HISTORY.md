# History feature

## Run locally

The default configuration now uses the team's PostgreSQL infrastructure. Start it
according to the root README, then run `.\mvnw.cmd spring-boot:run` from `backend`.
For optional local H2 use instead:
`.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=h2"`.
H2 stores data in memory, so restarting the application clears history.
Run tests using `.\mvnw.cmd test`.

## API

All examples use `http://localhost:8080` as the server. In Postman, choose the method,
enter the URL, and for POST choose Body -> raw -> JSON.

| Method | Path | Success |
| --- | --- | --- |
| POST | `/api/history?userId=user-123` | 201 with the created entry |
| GET | `/api/history?userId=user-123&page=0&size=20` | 200 with a JSON array |
| DELETE | `/api/history/10?userId=user-123` | 204 without a body |
| DELETE | `/api/history?userId=user-123` | 204 without a body |

POST body:

```json
{"videoId": 25}
```

Example POST response (ID and time are generated):

```json
{"id": 10, "videoId": 25, "watchedAt": "2026-10-26T10:00:00Z"}
```

GET returns an array of these objects. It sorts newest first, then by history ID
descending if times match. `page` starts at zero; `size` defaults to 20 and must be
1 through 100. Request the next page until an empty array is returned. Pagination
uses offsets, so new watches or deletions between requests can shift page boundaries.

Each POST records one watch event. Watching the same video twice creates two entries.
The server supplies the time. Deletion by ID removes a history entry, never the video.
Clearing an empty history succeeds. Deleting a missing entry or an entry belonging
to a different supplied user returns 404. Invalid input returns 400, including blank
user IDs, user IDs longer than 255 characters, invalid paging, nonpositive IDs,
missing video IDs, and malformed JSON. A missing JSON content type can return 415.

## How the Java classes work together

1. `HistoryController` reads HTTP input, validates it, and selects response statuses.
2. `HistoryRequest` holds the incoming video ID.
3. `HistoryService` performs operations in database transactions, assigns watch times,
   and converts entities to response DTOs. Read operations use read-only transactions.
4. `HistoryRepository` provides database operations through Spring Data JPA. Its
   derived method names express user filtering and ordering; `PageRequest` limits rows.
5. `WatchHistory` maps to the `watch_history` table. Its index supports queries by user
   and watch time. User/video IDs are scalar values until the other features are ready.
6. `HistoryResponse` defines the JSON returned to clients, without exposing the user ID.

Both History DTOs are Java records, following the team's DTO convention. Records
generate their constructor and accessors automatically: use `request.videoId()`
instead of `request.getVideoId()`. They have no setters. Request validation stays
on the record component, and the API JSON property names remain the same.

`HistoryApiTests` starts a real HTTP server and uses H2 to test validation, save/read,
repeat watches, ordering, paging, single deletion, and clearing. The tests check user
filtering, not authentication: the query parameter is still client-controlled.

## Team integration still required

- Replace every `userId` query parameter with identity from the authenticated user.
  The current API is a local learning API and does not prevent impersonating a user.
- Integrate video existence checks and agree the video ID type with the video developer.
  Titles/thumbnails can be added to response DTOs when video data is available.
- PostgreSQL now follows the shared `DATABASE_URL`, `DATABASE_USERNAME`, and
  `DATABASE_PASSWORD` configuration. History adds `schema-history.sql` to the team's
  SQL initialization list and preserves Hibernate's shared `ddl-auto=validate` setting.
  The H2 profile overrides SQL initialization and generates its tables locally.
- Run integration tests against PostgreSQL when available; passing H2 tests does not
  verify PostgreSQL behavior. No PostgreSQL connection is exercised by the current tests.

API documentation now comes from the team's Springdoc setup at `/swagger-ui.html`
and `/v3/api-docs`. The old static frontend OpenAPI file was removed upstream.

## Package layout

All production Java code is under `backend/src/main/java/ITubeBackend`:

```text
controller/HistoryController.java
service/history/HistoryService.java
repository/HistoryRepository.java
entities/WatchHistory.java
dto/history/HistoryRequest.java
dto/history/HistoryResponse.java
```

Package declarations and imports follow these folders. The main application needs
only the team's standard annotations; default scanning discovers History along with
the other features. Resources and tests remain in their standard Maven directories.

## Frontend History page

Run the backend, then run `npm run dev` from `frontend`. Open
`http://localhost:3000/history` or choose History in the navigation. The older
`/#history` link also works. The mobile layout includes Home and History links.
Vite proxies the API calls to port 8080, following the team's defaults.

The page loads real API entries with watch times, paging, refresh, removal, and
confirmation before clearing all history. Video titles, thumbnails, and playback
remain integration work for History, so entries display their video IDs. The team's
new video, channel, upload, and membership pages are preserved by the merge.

The development identity defaults to `user-123`, matching the Postman examples.
To change it locally, set `VITE_HISTORY_USER_ID=your-user-id` in
`frontend/.env.local` and restart Vite. This is not authentication.
Create records through POST using the same user ID to populate the page.
`frontend/src/api/history.js` exports `recordWatch(videoId)` for the future player;
call it when actual playback starts, not when a thumbnail is displayed.

Frontend tests use mocked HTTP responses to cover loading, errors, retries,
deletion, clearing confirmation, pagination, request cancellation, and navigation.
Run `npm test -- --run` and `npm run build` from `frontend`.
