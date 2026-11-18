# Cloudflare (DNS, CDN, Tunnel, edge security)

Terraform for ITube's public edge. Why and how it works: [docs/CDN-AND-DNS.md](../../docs/CDN-AND-DNS.md).

| File | What |
|---|---|
| [main.tf](main.tf) | looks up the zone; Smart Tiered Cache (the CDN's origin shield) |
| [tunnel.tf](tunnel.tf) | Cloudflare Tunnel, its route `app hostname -> frontend Service`, the app's DNS record |
| [cache.tf](cache.tf) | what the edge may cache: frontend assets, the home feed (10 s), thumbnail redirects, media |
| [media.tf](media.tf) | optional media CDN: DNS, origin and TLS rules towards S3, the bucket policy |
| [security.tf](security.tf) | edge rate limit on sign-in/registration |

It is a separate stack from [deploy/terraform](../terraform) (AWS): it has its own state, and you can apply it without
touching the cluster. State is shared in the encrypted, versioned S3 backend at
`s3://itube-tfstate-160198385987/itube/cloudflare/terraform.tfstate`, with S3 lockfiles enabled.
Run `terraform init -reconfigure` when switching from local state; do not overwrite this shared state.

## Prerequisites

- A domain whose DNS is on Cloudflare (its nameservers point at Cloudflare). Any plan, including Free.
- Your Cloudflare account id (Dashboard -> your domain -> Overview, bottom of the right column).
- An API token (My Profile -> API Tokens -> Create Token -> Custom token) with:
  - Zone: Zone Read, DNS Edit, Zone Settings Edit, Cache Rules Edit, Zone WAF Edit
  - For the optional media CDN: also Config Rules Edit and Origin Rules Edit
  - Account: Cloudflare Tunnel Edit
  - Zone Resources: only your domain
- AWS credentials for the S3 state backend (also used for the bucket policy when `enable_media_cdn = true`).

## Apply

```bash
cd deploy/cloudflare
export CLOUDFLARE_API_TOKEN=...      # PowerShell: $env:CLOUDFLARE_API_TOKEN='...'
terraform init
terraform plan  -var cloudflare_account_id=954308968932cd786fe197e0e9642054 -var zone_name=itube.fun -var app_subdomain=@
terraform apply -var cloudflare_account_id=954308968932cd786fe197e0e9642054 -var zone_name=itube.fun -var app_subdomain=@
```

Or put the non-secret variables in `terraform.tfvars` (git-ignored):

```hcl
cloudflare_account_id = "0123456789abcdef0123456789abcdef"
zone_name             = "example.com"
app_subdomain         = "itube"   # -> https://itube.example.com
```

## Connect the cluster

```bash
terraform output -raw tunnel_token | gh secret set CLOUDFLARE_TUNNEL_TOKEN --env dev --repo PawanITC/ITube
```

The next run of the deploy workflow sees the secret, creates the `cloudflare-tunnel` Kubernetes Secret and applies
[deploy/k8s-cloudflare](../k8s-cloudflare) (everything plus two `cloudflared` pods). Then:

- set `FRONTEND_BASE_URL` in [deploy/k8s/configmap.yaml](../k8s/configmap.yaml) to `terraform output -raw app_url`
  (Stripe sends buyers back there);
- add the same URL to `media_bucket_cors_origins` in deploy/terraform (browsers upload video parts straight to S3).

## Optional: the media CDN

Thumbnails (and, if you choose, video files) served from `https://media.<domain>` and cached at the edge.

1. deploy/terraform: `media_bucket_allow_public_policy = true`, apply (allows a bucket policy that grants public reads;
   the policy below only lets Cloudflare read objects tagged `public=true`).
2. Here: `enable_media_cdn = true`, apply.
3. [configmap.yaml](../k8s/configmap.yaml): `CDN_MEDIA_BASE_URL` = `terraform output -raw media_base_url`, deploy.

Video files stay on pre-signed S3 URLs unless you also set `CDN_SERVE_VIDEO=true` on catalog-service. Read the
warning about Cloudflare's terms in [docs/CDN-AND-DNS.md](../../docs/CDN-AND-DNS.md) first.
