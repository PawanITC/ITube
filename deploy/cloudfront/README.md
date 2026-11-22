# CloudFront CDN: private media and frontend assets

This stack is independent of EKS so the CDN demo works while the expensive dev cluster is shut down.
It creates one globally distributed CloudFront distribution, two private S3 origins (reusing the existing media
bucket), Origin Access Control (OAC), a trusted signing key group, and a narrowly scoped GitHub demo role.
It does not create EC2, EKS, NAT gateways, load balancers, Lambda@Edge, Origin Shield, or paid monitoring.

## Request paths

```text
Browser -> itube.fun -> Cloudflare Tunnel -> EKS frontend/API (when EKS is running)
Browser -> <distribution>.cloudfront.net/assets/* -> private assets bucket
Browser -> <distribution>.cloudfront.net/videos/* -> private media bucket (signed URL required)
```

Use the default CloudFront hostname initially: HTTPS is included, no certificate/DNS setup or stacked CDN.
Cloudflare continues serving the app/API. This is two S3 origins, not an API origin, multi-region application,
or origin failover. `PriceClass_All` enables all CloudFront edge regions; both S3 origins remain in London.
The cache fills on demand, not by copying every object to every edge in advance.

| Behavior | Origin | Viewer access | Edge TTL | Cache key |
| --- | --- | --- | --- | --- |
| `/assets/*` | Assets S3 | Public HTTPS | Origin headers; default/max 1 year | Path, normalized compression |
| Default, including `/videos/*` | Media S3 | Valid signature required, HTTPS only | Origin headers; default/max 1 hour | Path |

Both policies have minimum TTL zero, so origin `private`/`no-store` is respected. OAC signs origin requests;
bucket policies grant only this distribution access to the appropriate key prefix. OAC is not viewer authorization:
the media behavior's trusted key group checks signatures even on a cache hit. Different valid signatures reuse
the same cached bytes. Cookies, arbitrary query strings, and application JWTs are not forwarded to S3.
Media viewer responses get `private, no-store` so browsers recheck the edge instead of retaining private bytes
beyond URL expiry. That viewer response policy does not disable CloudFront's own object cache.

## Access and freshness

The existing playback authorization runs before `MediaCdn` signs the exact object URL. Default URL expiry is
five minutes, configurable from 30 seconds to 15 minutes. Signed redirects are `private, no-store`, including
public video redirects. Public listings may display members-only thumbnails; private thumbnails require ownership.
The signing key is PKCS#8 RSA-2048, separate from JWT and playback-token keys. The AWS SDK performs signing.

Visibility changes prevent new anonymous media grants after the database update. Existing signed URLs remain
usable until expiry; existing playback authorization tokens also have their own validity window. This is bounded
access, not instant revocation or DRM. For emergency revocation, remove the object and invalidate its exact path,
or rotate/revoke the trusted key (affects all users of that key). Edge invalidation alone does not revoke a valid URL
if the object remains available at the origin. A download already in progress may finish after signature expiry.

Frontend asset names and thumbnail keys change with their content. Retain old frontend assets for rollbacks/open
tabs. Rendition keys are currently reused on retranscode: invalidate that exact path after replacing a rendition
or allow up to the one-hour TTL. A future versioned-rendition migration would remove that overwrite trade-off.
MP4 Range requests support seeking; this is not an HLS/DASH adaptive streaming implementation.

## Setup and review

1. Sign in with `aws login --region eu-west-2`. Keep `deploy/cloudflare`'s `enable_media_cdn=false` and the main
   stack's `media_bucket_allow_public_policy=false`. CloudFront owns the media bucket policy; enabling the older
   Cloudflare media stack would create competing Terraform owners.
2. The public key is `signing-public-key.pem`. Its matching private key is in GitHub environment `dev`, secret
   `CLOUDFRONT_SIGNING_PRIVATE_KEY`. Never put a private key in Terraform variables/state, files, or logs.
3. Set the public input and review a saved plan in PowerShell:

   ```powershell
   $env:TF_VAR_signing_public_key_pem = Get-Content .\signing-public-key.pem -Raw
   terraform init
   terraform plan '-out=review.tfplan'
   ```

   Review before applying. The separate remote state key is `itube/cloudfront/terraform.tfstate`. The media bucket
   itself remains owned by `deploy/terraform`. The CDN stack must not destroy or replace it.
4. Restore the retained media bucket's public-access block if the dev teardown removed it. Its owner is still
   `deploy/terraform`. For this exceptional repair while EKS stays off, review/apply only
   `aws_s3_bucket_public_access_block.media` using a targeted saved plan; do not apply the full EKS stack.
   All four public-access-block flags must be true. This is a recovery step, not the routine deployment method.
5. After approved apply, set GitHub environment `dev` variables from Terraform outputs:

   | Variable | Output |
   | --- | --- |
   | `CLOUDFRONT_BASE_URL` | `cdn_base_url` |
   | `CLOUDFRONT_ASSETS_BUCKET` | `assets_bucket` |
   | `CLOUDFRONT_SIGNING_KEY_ID` | `signing_key_id` |
   | `CLOUDFRONT_DEMO_ROLE_ARN` | `demo_role_arn` |

6. Run **CloudFront CDN acceptance demo** on the branch containing this code. It uses no EKS. It uploads two 4 KiB
   objects to unique `cdn-demo` prefixes, checks them, and deletes them in `finally`. Temporary cached copies may
   remain until TTL, but contain only synthetic data. It never prints signed URLs. On failure it exits nonzero.
7. For the full app, restore EKS separately only for the demonstration. Set this stack's `deploy_role_name` to the
   existing app deployment role, review/apply its asset-upload policy, then run **Deploy to EKS (dev)** with a new
   commit SHA. The frontend Docker build embeds the CDN URL. The workflow extracts and publishes the exact image's
   assets before rollout, injects the signing secret via stdin, and configures the catalog. An image built before
   CDN activation fails the CDN check rather than silently deploying mismatched assets. When finished, tear down
   the temporary compute with the team's reviewed EKS teardown procedure; retain media, ECR, and CDN resources.

## Verification and senior demo (about 8 minutes)

1. CloudFront console -> distribution -> **Origins**: show two regional S3 endpoints. Explain that origins are
   sources of content, while edge locations are worldwide caches. Show **Behaviors** and the assets/default split.
2. Show **Security / trusted key group** settings and S3 **Permissions**. Explain OAC versus viewer signatures.
3. Run the acceptance workflow. Show its summary: two cache hits, `Age`, `X-Amz-Cf-Pop`, different signatures sharing
   bytes, HTTP 206/Content-Range, unsigned/tampered/expired media returning 403 even after warming the cache, and
   direct anonymous S3 returning 403. These are assertions, not screenshots of configuration alone.
4. When EKS is on, open the app's Network panel: JS/CSS use `/assets/` on CloudFront; playback redirects go to signed
   CloudFront URLs; `/api/` still goes to itube.fun. Hide the signed query strings/cookies when sharing the screen.
5. Explain the freshness/revocation trade-offs above. Show the backend signer, cache policies, and backend tests.
6. CloudWatch -> Metrics -> CloudFront (global metrics in us-east-1): show default Requests, BytesDownloaded, and
   error-rate metrics. Do not enable paid additional metrics just for the demo. A single `X-Amz-Cf-Pop` proves only
   that request's edge location; run the same small public-asset check from a second geographic location to compare.
   Do not claim globally measured latency, origin offload percentages, or 100-million-user capacity without data.

## Cost envelope: additional target £5/month

Pricing checked 2026-11-22. AWS pay-as-you-go currently includes 1 TB transfer out and 10 million HTTP(S) requests
per month; allowances are shared at account level, not dedicated to this distribution. S3-to-CloudFront transfer
is free. There is no fixed monthly distribution or OAC charge. S3 storage/requests and optional invalidations
remain billable. Do not assume remaining free allowance without checking the account's Billing page.

Demo operating envelope: <=1 GB additional stored objects, <=5 GB viewer transfer/month, <=10,000 viewer requests,
<=10,000 S3 GETs and <=1,000 PUTs, <=100 invalidation paths. With unused CloudFront allowance the incremental bill
should be small (mostly S3). Even without it, this deliberately small workload is expected below £5, subject to
regional rates, tax and exchange rates. This estimate is not a hard limit; unbounded public traffic can exceed it.
Set billing alerts against the £5 equivalent in the account's billing currency and check usage before each demo.
Budget alerts do not stop traffic. Disable this distribution when retiring the demo; keep EKS off between sessions.
The automated acceptance run transfers only a few tens of KiB and cleans its origin fixtures.

References:
- https://aws.amazon.com/cloudfront/pricing/pay-as-you-go/
- https://aws.amazon.com/s3/pricing/
- https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-restricting-access-to-s3.html
- https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-signed-urls.html
- https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/DownloadDistValuesCacheBehavior.html

## Tests and rollback

`terraform init -backend=false; terraform validate; terraform test` uses a mock provider for tests (no AWS writes).
`cd backend; .\mvnw.cmd test` covers the signer, tampering, expiry value, private thumbnail access, signed-redirect
cache controls, plus the existing service suites. `cd frontend; npm test -- --run; npm run build` verifies the app.
Live acceptance is a separate explicit workflow; a passing local test does not mean AWS has been deployed.

To revert application routing, clear `CLOUDFRONT_BASE_URL`, rebuild with a new SHA, and deploy; the catalog returns
to pre-signed S3 and the frontend serves its local assets. Keep the distribution alive while existing signed URLs
expire. Old files are intentionally not deleted by deploys. Before destroying this stack, review all users and
explicitly empty only its assets bucket if appropriate (`force_destroy=false` prevents accidental data deletion).
Do not rotate the public key without updating the matching GitHub secret and redeploying catalog pods.
