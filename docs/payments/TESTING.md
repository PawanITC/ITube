# Running and testing payments locally (Stripe sandbox)

This guide covers setting up the Stripe integration on your machine, testing it end to end with fake cards, and what still needs configuring in the Stripe Dashboard. For how it works, see [ARCHITECTURE.md](ARCHITECTURE.md).

> Everything here uses Stripe **test mode / sandbox** keys (`sk_test_…`, `rk_test_…`). No real money moves. The backend **refuses to start** with a live key unless `STRIPE_ALLOW_LIVE_MODE=true`.

## 1. One-time setup

### Stripe keys
1. In the Stripe Dashboard, switch to your sandbox, then open **Developers → API keys**.
2. Prefer a **restricted key** (`rk_test_…`) over the full secret key. It needs **Write** on Customers, Checkout Sessions, Products, Prices, Subscriptions, Customer portal, and Refunds (refunds are only used to undo an accidental duplicate), and **Read** on Invoices, Invoice Payments, Charges, Disputes and Events.
3. Copy `backend/.env.example` to `backend/.env` and fill it in. `.env` is git-ignored. **Never commit it, and never paste keys into code, chat or tickets.**

```properties
STRIPE_SECRET_KEY=rk_test_...
STRIPE_WEBHOOK_SECRET=whsec_...      # from step 3 below
PLAYBACK_SIGNING_KEY=...             # openssl rand -base64 32
PLATFORM_FEE_BPS=0                   # commission in basis points; business decision
```

### Infrastructure
On Windows, `.\dev.cmd` from the repo root starts everything in this section for you (see the README's Quick start). By hand, start PostgreSQL, Redis and Kafka with `make up` in `tf_docker/`, or with [docker-compose.yml](../../docker-compose.yml) from the repo root:

```bash
docker compose --env-file backend/.env up -d
```

### Stripe CLI (forwards webhooks to localhost)
Install the [Stripe CLI](https://docs.stripe.com/stripe-cli), then:

```bash
stripe login                       # once
stripe listen --forward-to localhost:8080/api/webhooks/stripe \n  --events checkout.session.completed,checkout.session.async_payment_succeeded,checkout.session.async_payment_failed,checkout.session.expired,customer.subscription.created,customer.subscription.updated,customer.subscription.deleted,customer.subscription.paused,customer.subscription.resumed,invoice.paid,invoice.payment_failed,charge.refunded,charge.dispute.created,charge.dispute.updated,charge.dispute.closed,radar.early_fraud_warning.created
```

Current CLI versions refuse to start without `--events`. The list above is exactly the events the backend handles.

`stripe listen` prints a `whsec_…` secret. Put it in `STRIPE_WEBHOOK_SECRET` and restart the backend. Leave `stripe listen` running while you test. Without it no webhooks arrive, and memberships stay "confirming" forever. **That is by design: the app never grants access from the Checkout redirect alone.**

## 2. Run it

```bash
cd backend && ./mvnw spring-boot:run      # Windows: mvnw.cmd spring-boot:run
cd frontend && npm install && npm run dev  # http://localhost:3000
```

The log should show `Stripe client ready (test mode)`. If it says `STRIPE_SECRET_KEY is not set`, the payment endpoints return `503` and everything else still works.

## 3. Manual test script

Use two browsers, or one normal window and one private window, so you have two separate sessions.

| # | As | Do | Expect |
|---|---|---|---|
| 1 | Creator (window A) | Click the logo to start the demo session, then open your channel page | A **Channel memberships** panel |
| 2 | Creator | Add "Supporter", level 1, £2.99, and "VIP", level 3, £9.99 | Both listed. Products appear in Stripe under **Product catalogue** |
| 3 | Creator | Upload a video with **Visibility: Members only** | It shows a green "Members only" badge in the feed |
| 4 | Viewer (window B) | Click **Random user**, open the video | Locked player: "Join the channel to watch it" |
| 5 | Viewer | Try `http://localhost:8080/api/videos/<id>/file` directly | `403` |
| 6 | Viewer | Click **Join to watch** → **Join** on Supporter | Redirected to Stripe Checkout |
| 7 | Viewer | Pay with `4242 4242 4242 4242`, any future date, any CVC and postcode | Back on iTube: "Confirming your payment…", then "Welcome, you're a member!" |
| 8 | Viewer | Open the video again | It plays |
| 9 | Viewer | Click Join again on the channel page | Shows **Member · Supporter**; no second checkout is possible |
| 10 | Viewer | **Memberships** (sidebar) → change tier to VIP | Prorated charge, tier changes, and VIP-only videos unlock |
| 11 | Viewer | **Cancel** | "Ends <date>". Access continues until then |
| 12 | Viewer | **Manage billing** | Stripe Customer Portal opens (needs portal config, see §5) |

Things to check alongside:
- `stripe listen` shows each event with `[200]`.
- Kafka: `kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic membership.activated --from-beginning` shows one event per activation.
- Database: `select * from webhook_inbox order by webhook_inbox_id desc;` shows rows with `PROCESSED` or `IGNORED`, none stuck in `RECEIVED`.

### Test cards and scenarios

| Scenario | How |
|---|---|
| Successful payment | `4242 4242 4242 4242` |
| Card declined at checkout | `4000 0000 0000 0002` |
| 3D Secure challenge | `4000 0025 0000 3155` |
| Renewal fails later (past_due) | Join with `4000 0000 0000 0341` (attaches but fails on charge), or use a [test clock](https://docs.stripe.com/billing/testing/test-clocks) to advance a month |
| Expiry at period end | Cancel, then advance a test clock past the period end. You should see `customer.subscription.deleted`, access removed, and `membership.expired` |
| Duplicate / out-of-order webhooks | `stripe events resend <evt_id>`. The inbox ignores the duplicate and nothing changes |
| Refund | Refund the payment in the Dashboard. A `refund_records` row is added and the payment becomes `REFUNDED` |
| Dispute | Pay with `4000 0000 0000 0259`. The payment becomes `DISPUTED` and a warning is logged |
| Invalid signature | `curl -X POST localhost:8080/api/webhooks/stripe -H 'Stripe-Signature: t=1,v1=bad' -d '{}'` returns `400` |

## 4. Automated tests

```bash
cd backend && ./mvnw test     # 52 tests, Stripe mocked, no network or DB needed
cd frontend && npm test       # Join button / tier modal behaviour
```

| Area | Test class |
|---|---|
| Checkout params, duplicate checkout, reuse/expire sessions, own channel, rate limit | `CheckoutServiceTest` |
| Duplicate and out-of-order webhooks, cancellation, failed renewal, upgrade, duplicate subscription | `MembershipSyncServiceTest` |
| Signature rejection, inbox dedupe, failed/paid invoices, refunds, disputes, dead-lettering | `StripeWebhookTest` |
| Direct access to paid media without a token, tier gating, expiry, private videos | `MembersOnlyPlaybackTest` |
| Token expiry, tampering, wrong video, wrong key | `PlaybackTokenServiceTest` |
| Access policy per Stripe status, grace period | `MembershipAccessPolicyTest` |
| Upgrade/downgrade proration, failed upgrade, cancel, other users' memberships | `MembershipServiceTest` |
| Free Subscribe never touches Stripe | `FreeSubscribeTest` |

## 5. Needs action in the Stripe Dashboard

The code can't do these. Each needs a decision or approval from you.

| # | What | Why | Where |
|---|---|---|---|
| 1 | **Managed Payments** (done in code) | It's on by default in this account and would make Stripe the merchant of record, which doesn't fit the Connect marketplace plan. Membership Checkouts now send `managed_payments.enabled=false` (`STRIPE_MANAGED_PAYMENTS`). Optionally also switch the account default off so other flows don't pick it up. | Settings → Managed Payments |
| 2 | **Customer Portal** (sandbox done) | The sandbox default config already has plan switching **off** (tier changes must go through iTube so a membership can't be switched onto another channel's tier), cancel at period end, and payment method updates. **Live mode has its own configuration**: copy these settings before launch. | Settings → Billing → Customer portal |
| 3 | Create the **production webhook endpoint** | `stripe listen` only works locally. Point it at `https://<domain>/api/webhooks/stripe` and subscribe to the events in ARCHITECTURE §7. Use the signing secret it gives you in production only. | Developers → Webhooks |
| 4 | **Revenue recovery** | Turn on Smart Retries, failed-payment emails and automatic card updates. Choose what happens after the last retry (cancel or mark unpaid); this decides how long members keep access after a failed payment. | Settings → Billing → Revenue recovery |
| 5 | **Tax** | Confirm the head-office address and turn on Stripe Tax threshold monitoring (free; alerts you before a VAT registration is needed). Have an accountant confirm the tax code `txcd_10402200`. | Settings → Tax |
| 6 | **Rotate the secret key that was shared in chat** | A key that has been pasted anywhere outside a secrets store should be treated as exposed. Roll it and switch to a restricted key. | Developers → API keys |
| 7 | Set the **platform commission** | `PLATFORM_FEE_BPS` defaults to 0. | Business decision |

## 6. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| "The payment provider could not complete the request" | Set `STRIPE_DETAILED_ERRORS=true` in `backend/.env` (test keys only; ignored with a live key) and the app shows Stripe's actual reason. The backend log always has it: look for `Stripe call failed … message=…`, and open the `requestId` in the Dashboard's request logs. |
| Stuck on "Confirming your payment…" | Webhooks aren't arriving. Is `stripe listen` running, and does `STRIPE_WEBHOOK_SECRET` match its output? |
| Webhook returns `400 Invalid signature` | The secret is wrong, or something re-serialised the body. The endpoint must receive the raw bytes. |
| `webhook_inbox` rows with `FAILED` status | Check `last_error`. After fixing the cause, set `status='RECEIVED', attempts=0, next_attempt_at=now()` to replay them. |
| Backend logs `Failed to connect to localhost:4318` every 10s | The monitoring container isn't running. It's harmless, but start `grafana-lgtm` (it's in the compose file and in `tf_docker`). Grafana is then at http://localhost:3080. |
| Members-only video plays for a while after cancelling | Expected. An issued token lasts 10 minutes plus the video's length (max 6 hours) so streaming doesn't stall. New tokens are refused straight away. |
