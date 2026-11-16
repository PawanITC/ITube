-- ScyllaDB schema for engagement data (watch history, likes, comments).
-- Applied on startup when itube.scylla.create-schema=true; every statement must be idempotent.
-- {keyspace} and {replication} are filled in from itube.scylla.* settings.
--
-- Design rules: one table per query, partitioned so every read hits a single partition, and
-- no cross-table joins. Video and account details are looked up in Postgres by id when needed.

-- Tablets (ScyllaDB 6+ default) don't support lightweight transactions yet, which like_lookup needs,
-- so this keyspace uses classic vnode token distribution. Revisit when LWT works with tablets.
CREATE KEYSPACE IF NOT EXISTS {keyspace} WITH replication = {replication} AND tablets = {'enabled': false};

-- GET /api/history: a user's history, newest first. entry_id is a timeuuid, so it is both the
-- public id of an entry and its sort order. ~100 bytes a row: years of heavy viewing stay well
-- under 1 MB per partition.
CREATE TABLE IF NOT EXISTS {keyspace}.watch_history_by_user (
    user_id bigint,
    entry_id timeuuid,
    video_id bigint,
    watched_at timestamp,
    PRIMARY KEY ((user_id), entry_id)
) WITH CLUSTERING ORDER BY (entry_id DESC);

-- GET /api/liked-videos: a user's likes, newest first.
CREATE TABLE IF NOT EXISTS {keyspace}.likes_by_user (
    user_id bigint,
    liked_at timestamp,
    video_id bigint,
    PRIMARY KEY ((user_id), liked_at, video_id)
) WITH CLUSTERING ORDER BY (liked_at DESC, video_id DESC);

-- "Does this user like this video?" and the like's time (needed to delete it from likes_by_user).
-- Written with lightweight transactions (IF NOT EXISTS / IF EXISTS) so double-clicks can't duplicate a like.
CREATE TABLE IF NOT EXISTS {keyspace}.like_lookup (
    user_id bigint,
    video_id bigint,
    liked_at timestamp,
    PRIMARY KEY ((user_id, video_id))
);

-- Who liked a video: lets the video.deleted consumer remove those likes.
CREATE TABLE IF NOT EXISTS {keyspace}.likes_by_video (
    video_id bigint,
    user_id bigint,
    PRIMARY KEY ((video_id), user_id)
);

-- GET /api/videos/{id}/comments: newest first. author_name is copied in at write time.
CREATE TABLE IF NOT EXISTS {keyspace}.comments_by_video (
    video_id bigint,
    comment_id timeuuid,
    user_id bigint,
    author_name text,
    content text,
    created_at timestamp,
    PRIMARY KEY ((video_id), comment_id)
) WITH CLUSTERING ORDER BY (comment_id DESC);
