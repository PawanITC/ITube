# CDN and DNS with Cloudflare

ITube's public edge runs on Cloudflare's free plan, managed with Terraform in [deploy/cloudflare](../deploy/cloudflare).

```
visitor ──HTTPS──► Cloudflare edge (nearest of ~300 cities)
                    │  DNS · TLS · cache · WAF · DDoS protection · rate limit
                    │
                    ├─ itube.<domain>  ──Cloudflare Tunnel──► cloudflared pods ─► frontend ─► gateway ─► services
                    └─ media.<domain>  ──HTTPS──────────────► S3 media bucket (public-tagged objects only)
```

## Why a CDN is the most important scaling piece

At YouTube's scale over 90% of all bytes are served by CDN edge servers, not by YouTube's own servers. The reason
is arithmetic: 100 million people watching 40 minutes of 720p video a day is roughly 7 Tbps on average and around
20 Tbps at peak (see [RESILIENCE-AND-SCALE.md](RESILIENCE-AND-SCALE.md)). No origin can serve that, and paying S3
egress for it would cost millions per day. A CDN keeps copies close to viewers, so:

- **latency**: a viewer in Mumbai gets bytes from Mumbai, not from London (eu-west-2);
- **origin load**: a popular file is fetched from S3 once per region, not once per viewer;
- **cost**: CDN delivery is far cheaper than cloud egress (and free on Cloudflare's plans, within their terms).

## What goes through Cloudflare

| Traffic | How | Cached at the edge? |
|---|---|---|
| The app (HTML, JS, CSS) | `itube.<domain>` -> Tunnel -> frontend | `/assets/*` for 1 year (hashed file names) |
| Home feed `GET /api/videos` | -> gateway -> catalog | 10 s (`Cache-Control: public, max-age=10`) |
| Thumbnail redirects | -> catalog | 10 min |
| Everything per-user (history, likes, uploads, sign-in...) | -> gateway -> service | never (`private, no-store`, cookies) |
| Thumbnails (when `enable_media_cdn`) | `media.<domain>` -> S3 | 30 days (object keys never change) |
| Public video files | only with `CDN_SERVE_VIDEO=true`, see the warning below | 30 days |
| Members-only and private media | always pre-signed S3 URLs, never the CDN | never |

Cloudflare's **Smart Tiered Cache** (free) adds a regional "upper tier": an edge that doesn't have a file asks its
upper tier before asking the origin. That is the CDN's *origin shield*: a newly popular file is fetched from S3 once
per region instead of once per edge location, which protects the origin from a thundering herd.

### How public media stays public-only

- catalog-service redirects public thumbnails (and videos, if enabled) to `https://media.<domain>/<key>`. That URL is
  the same for every viewer, so the CDN can cache it. Pre-signed URLs are different per viewer and can't be shared
  from a cache.
- The bucket policy ([media.tf](../deploy/cloudflare/media.tf)) allows anonymous reads **only** from Cloudflare's IP
  ranges **and only** for objects tagged `public=true`.
- catalog-service tags a video's files public when the video is public or unlisted, and untags them when it becomes
  private or members-only ([MediaCdn](../backend/catalog-service/src/main/java/ITubeBackend/cdn/MediaCdn.java)).
  Thumbnails of members-only videos stay public because they're shown in listings.

### Warning: video files on Cloudflare's free CDN

Cloudflare's terms only allow serving video and other large files through its CDN if you use one of its paid video
products (Stream) or store the files in Cloudflare **R2**. HTML, API responses and images (thumbnails) are fine on
the free plan. That's why `CDN_SERVE_VIDEO` defaults to `false`: thumbnails come from the CDN, and video files keep
coming from S3 through pre-signed URLs. Before turning it on, choose one of these:

- move the media bucket to **R2** (S3-compatible, so `itube.storage.*` just points at R2's endpoint; no egress fees);
- use Cloudflare Stream; or
- put **Amazon CloudFront** in front of S3 for video files only (AWS's own CDN), and keep Cloudflare for the rest.

Read the current version of Cloudflare's Service-Specific Terms; they change.

## Why a Tunnel instead of a load balancer

The EKS cluster has no public endpoint (deliberately, to save cost). A Cloudflare Tunnel keeps it that way:
`cloudflared` pods ([deploy/k8s-cloudflare](../deploy/k8s-cloudflare)) open outbound connections to Cloudflare, and
Cloudflare sends visitors' requests down them. So:

- no AWS load balancer to pay for and no inbound port or security group opening;
- the origin can't be attacked directly, bypassing Cloudflare's WAF and DDoS protection;
- two connectors on different nodes, so a restart or a lost node doesn't take the site down.

## Edge protection

- **DDoS protection**: always on, every plan.
- **Rate limiting** ([security.tf](../deploy/cloudflare/security.tf)): sign-in and registration, 20 per IP per 10 s,
  blocked at the edge before reaching the cluster. The gateway has stricter limits as a second layer.
- **TLS**: Cloudflare terminates HTTPS with its own certificate for your hostname. The tunnel to the cluster is
  encrypted too.

## Setting it up (one time)

You need a domain whose DNS is on Cloudflare (Dashboard -> Add a site, then change the domain's nameservers at the
registrar to the two Cloudflare gives you). Then:

1. **API token**: Cloudflare Dashboard -> My Profile -> API Tokens -> Create Token -> Custom. Permissions: *Zone:
   DNS Edit, Zone Settings Edit, Cache Rules Edit, Config Rules Edit, Transform Rules Edit, Zone WAF Edit* and
   *Account: Cloudflare Tunnel Edit*. Limit it to your zone and account.
2. **Apply** ([deploy/cloudflare/README.md](../deploy/cloudflare/README.md) has the details):
   ```bash
   cd deploy/cloudflare
   export CLOUDFLARE_API_TOKEN=...          # never commit it
   terraform init
   terraform apply -var cloudflare_account_id=<id> -var zone_name=<your domain>
   ```
3. **Connect the cluster**: put the tunnel token into GitHub (environment `dev`):
   ```bash
   terraform output -raw tunnel_token | gh secret set CLOUDFLARE_TUNNEL_TOKEN --env dev --repo PawanITC/ITube
   ```
   The next deploy adds the `cloudflared` pods. ITube is then at `terraform output app_url`.
4. **Point Stripe redirects at it**: set `FRONTEND_BASE_URL` in [configmap.yaml](../deploy/k8s/configmap.yaml) to the
   app URL, and add the app URL to `media_bucket_cors_origins` in deploy/terraform (browser uploads to S3).
5. **Optional, the media CDN**: in deploy/terraform set `media_bucket_allow_public_policy = true` and apply; in
   deploy/cloudflare set `enable_media_cdn = true` and apply; then set `CDN_MEDIA_BASE_URL` in configmap.yaml to
   `terraform output media_base_url`.

## Checking it works

```bash
curl -sI https://itube.<domain>/ | grep -i -E "server|cf-ray"                  # server: cloudflare
curl -sI https://itube.<domain>/api/videos | grep -i cf-cache-status           # MISS, then HIT within 10 s
curl -sI https://media.<domain>/videos/<id>/thumbnail-<id> | grep -i cf-cache  # HIT after the first request
```
