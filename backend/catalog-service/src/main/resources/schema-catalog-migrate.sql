-- Runs first, on the ORIGINAL catalog database only (physical shard 0, PostgreSQL), on every start of
-- catalog-service. Moves catalog tables out of "public" for databases created before the domain split.
-- (identity-service and payments-service move their own tables.) Every statement is a no-op once done.
--
-- Nothing may reference a table in another schema: no foreign keys and no joins across domains.
CREATE SCHEMA IF NOT EXISTS catalog;
-- Old engagement tables wait here until engagement-service (LegacyEngagementMigration) has copied them to ScyllaDB
CREATE SCHEMA IF NOT EXISTS legacy_engagement;

ALTER TABLE IF EXISTS public.channel SET SCHEMA catalog;
ALTER TABLE IF EXISTS public.subscriptions SET SCHEMA catalog;
ALTER TABLE IF EXISTS public.video SET SCHEMA catalog;
ALTER TABLE IF EXISTS public.video_rendition SET SCHEMA catalog;

ALTER TABLE IF EXISTS public.watch_history SET SCHEMA legacy_engagement;
ALTER TABLE IF EXISTS public.liked_video SET SCHEMA legacy_engagement;
ALTER TABLE IF EXISTS public.video_comment SET SCHEMA legacy_engagement;

-- Foreign keys that crossed domains (PostgreSQL's default <table>_<column>_fkey names)
ALTER TABLE IF EXISTS catalog.channel DROP CONSTRAINT IF EXISTS channel_user_id_fkey;
ALTER TABLE IF EXISTS catalog.subscriptions DROP CONSTRAINT IF EXISTS subscriptions_user_id_fkey;


-- The legacy engagement tables must not block deleting a video or account while they wait to be copied
ALTER TABLE IF EXISTS legacy_engagement.liked_video DROP CONSTRAINT IF EXISTS liked_video_user_id_fkey;
ALTER TABLE IF EXISTS legacy_engagement.liked_video DROP CONSTRAINT IF EXISTS liked_video_video_id_fkey;
ALTER TABLE IF EXISTS legacy_engagement.video_comment DROP CONSTRAINT IF EXISTS video_comment_video_id_fkey;
ALTER TABLE IF EXISTS legacy_engagement.video_comment DROP CONSTRAINT IF EXISTS video_comment_user_id_fkey;
