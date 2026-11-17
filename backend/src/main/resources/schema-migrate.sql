-- Runs first on every start. Creates one schema per domain and moves tables from databases created
-- before the split (everything used to live in "public"). Every statement is a no-op once done.
--
--   identity  accounts                                   -> may move to its own database
--   catalog   channels, subscriptions, videos, renditions -> may move to its own database
--   payments  everything Stripe/membership related        -> may move to its own database
--   engagement (history, likes, comments)                 -> ScyllaDB, see scylla/engagement.cql
--
-- Nothing may reference a table in another schema: no foreign keys and no joins across domains.
CREATE SCHEMA IF NOT EXISTS identity;
CREATE SCHEMA IF NOT EXISTS catalog;
CREATE SCHEMA IF NOT EXISTS payments;
-- Old engagement tables wait here until LegacyDataMigration has copied them to ScyllaDB
CREATE SCHEMA IF NOT EXISTS legacy_engagement;

ALTER TABLE IF EXISTS public.accounts SET SCHEMA identity;

ALTER TABLE IF EXISTS public.channel SET SCHEMA catalog;
ALTER TABLE IF EXISTS public.subscriptions SET SCHEMA catalog;
ALTER TABLE IF EXISTS public.video SET SCHEMA catalog;
ALTER TABLE IF EXISTS public.video_rendition SET SCHEMA catalog;

ALTER TABLE IF EXISTS public.payment_customers SET SCHEMA payments;
ALTER TABLE IF EXISTS public.channel_membership_tiers SET SCHEMA payments;
ALTER TABLE IF EXISTS public.membership_perks SET SCHEMA payments;
ALTER TABLE IF EXISTS public.checkout_sessions SET SCHEMA payments;
ALTER TABLE IF EXISTS public.channel_memberships SET SCHEMA payments;
ALTER TABLE IF EXISTS public.premium_subscriptions SET SCHEMA payments;
ALTER TABLE IF EXISTS public.payments SET SCHEMA payments;
ALTER TABLE IF EXISTS public.refund_records SET SCHEMA payments;
ALTER TABLE IF EXISTS public.creator_stripe_accounts SET SCHEMA payments;
ALTER TABLE IF EXISTS public.creator_earnings SET SCHEMA payments;
ALTER TABLE IF EXISTS public.webhook_inbox SET SCHEMA payments;
ALTER TABLE IF EXISTS public.outbox_events SET SCHEMA payments;

ALTER TABLE IF EXISTS public.watch_history SET SCHEMA legacy_engagement;
ALTER TABLE IF EXISTS public.liked_video SET SCHEMA legacy_engagement;
ALTER TABLE IF EXISTS public.video_comment SET SCHEMA legacy_engagement;

-- Foreign keys that crossed domains (PostgreSQL's default <table>_<column>_fkey names)
ALTER TABLE IF EXISTS catalog.channel DROP CONSTRAINT IF EXISTS channel_user_id_fkey;
ALTER TABLE IF EXISTS catalog.subscriptions DROP CONSTRAINT IF EXISTS subscriptions_user_id_fkey;

ALTER TABLE IF EXISTS payments.payment_customers DROP CONSTRAINT IF EXISTS payment_customers_user_id_fkey;
ALTER TABLE IF EXISTS payments.channel_membership_tiers DROP CONSTRAINT IF EXISTS channel_membership_tiers_channel_id_fkey;
ALTER TABLE IF EXISTS payments.checkout_sessions DROP CONSTRAINT IF EXISTS checkout_sessions_user_id_fkey;
ALTER TABLE IF EXISTS payments.checkout_sessions DROP CONSTRAINT IF EXISTS checkout_sessions_channel_id_fkey;
ALTER TABLE IF EXISTS payments.channel_memberships DROP CONSTRAINT IF EXISTS channel_memberships_user_id_fkey;
ALTER TABLE IF EXISTS payments.channel_memberships DROP CONSTRAINT IF EXISTS channel_memberships_channel_id_fkey;
ALTER TABLE IF EXISTS payments.premium_subscriptions DROP CONSTRAINT IF EXISTS premium_subscriptions_user_id_fkey;
ALTER TABLE IF EXISTS payments.payments DROP CONSTRAINT IF EXISTS payments_user_id_fkey;
ALTER TABLE IF EXISTS payments.payments DROP CONSTRAINT IF EXISTS payments_channel_id_fkey;
ALTER TABLE IF EXISTS payments.creator_stripe_accounts DROP CONSTRAINT IF EXISTS creator_stripe_accounts_user_id_fkey;
ALTER TABLE IF EXISTS payments.creator_earnings DROP CONSTRAINT IF EXISTS creator_earnings_creator_user_id_fkey;
ALTER TABLE IF EXISTS payments.creator_earnings DROP CONSTRAINT IF EXISTS creator_earnings_channel_id_fkey;

-- The legacy engagement tables must not block deleting a video or account while they wait to be copied
ALTER TABLE IF EXISTS legacy_engagement.liked_video DROP CONSTRAINT IF EXISTS liked_video_user_id_fkey;
ALTER TABLE IF EXISTS legacy_engagement.liked_video DROP CONSTRAINT IF EXISTS liked_video_video_id_fkey;
ALTER TABLE IF EXISTS legacy_engagement.video_comment DROP CONSTRAINT IF EXISTS video_comment_video_id_fkey;
ALTER TABLE IF EXISTS legacy_engagement.video_comment DROP CONSTRAINT IF EXISTS video_comment_user_id_fkey;
