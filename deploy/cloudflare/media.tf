# Public media CDN: https://<media_subdomain>.<zone>/<object key> served from the S3 media bucket and cached at the edge.
# catalog-service redirects public thumbnails (and, with CDN_SERVE_VIDEO=true, public videos) here once
# CDN_MEDIA_BASE_URL=https://<media hostname> is set in deploy/k8s/configmap.yaml.
#
# Who can read what:
#   - only requests from Cloudflare's IP ranges (nobody can bypass the CDN and hit the bucket directly), and
#   - only objects tagged public=true. catalog-service tags a video's files when they are public and untags them
#     when the video becomes private or members-only, so paid and private media never leave through the CDN.
# Everything else in the bucket stays private (pre-signed URLs only), as before.

resource "cloudflare_dns_record" "media" {
  count = var.enable_media_cdn ? 1 : 0

  zone_id = local.zone_id
  name    = local.media_hostname
  type    = "CNAME"
  content = local.s3_hostname
  proxied = true
  ttl     = 1
  comment = "ITube public media from S3 (managed by deploy/cloudflare)"
}

# S3 picks the bucket from the Host header, so requests must arrive with the bucket's own hostname, not ours
resource "cloudflare_ruleset" "origin" {
  count = var.enable_media_cdn ? 1 : 0

  zone_id     = local.zone_id
  name        = "ITube origin rules"
  description = "Send media requests to S3 with the bucket's Host header (managed by deploy/cloudflare)"
  kind        = "zone"
  phase       = "http_request_origin"

  rules = [{
    description = "Media hostname -> S3 bucket"
    expression  = "(http.host eq \"${local.media_hostname}\")"
    action      = "route"
    action_parameters = {
      host_header = local.s3_hostname
    }
    enabled = true
  }]
}

# The S3 certificate is for *.s3.<region>.amazonaws.com, not our hostname, so for this one hostname Cloudflare
# connects with TLS but without checking the name ("Full", not "Full (strict)"). The visitor's side is unaffected.
resource "cloudflare_ruleset" "config" {
  count = var.enable_media_cdn ? 1 : 0

  zone_id     = local.zone_id
  name        = "ITube configuration rules"
  description = "TLS mode towards S3 for the media hostname (managed by deploy/cloudflare)"
  kind        = "zone"
  phase       = "http_config_settings"

  rules = [{
    description = "Media hostname: SSL Full towards S3"
    expression  = "(http.host eq \"${local.media_hostname}\")"
    action      = "set_config"
    action_parameters = {
      ssl = "full"
    }
    enabled = true
  }]
}

data "cloudflare_ip_ranges" "edge" {}

data "aws_s3_bucket" "media" {
  count  = var.enable_media_cdn ? 1 : 0
  bucket = var.media_bucket_name
}

data "aws_iam_policy_document" "media_via_cloudflare" {
  count = var.enable_media_cdn ? 1 : 0

  statement {
    sid       = "CloudflareReadsPublicMediaOnly"
    actions   = ["s3:GetObject"]
    resources = ["${data.aws_s3_bucket.media[0].arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "IpAddress"
      variable = "aws:SourceIp"
      values   = concat(data.cloudflare_ip_ranges.edge.ipv4_cidrs, data.cloudflare_ip_ranges.edge.ipv6_cidrs)
    }

    condition {
      test     = "StringEquals"
      variable = "s3:ExistingObjectTag/public"
      values   = ["true"]
    }
  }
}

# The bucket's only bucket policy (deploy/terraform manages the bucket itself and grants the app through its IAM role)
resource "aws_s3_bucket_policy" "media" {
  count = var.enable_media_cdn ? 1 : 0

  bucket = data.aws_s3_bucket.media[0].id
  policy = data.aws_iam_policy_document.media_via_cloudflare[0].json
}
