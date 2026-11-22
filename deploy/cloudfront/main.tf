# Isolated stack: no changes to the EKS/VPC lifecycle. Both origins use S3 REST endpoints, never website endpoints.
variable "media_bucket_name" {
  type    = string
  default = "itube-media-160198385987"
}

variable "signing_public_key_pem" {
  description = "RSA-2048 public key only. Keep the matching PKCS#8 private key out of Terraform/state."
  type        = string
  validation {
    condition     = startswith(trimspace(var.signing_public_key_pem), "-----BEGIN PUBLIC KEY-----")
    error_message = "Provide the PUBLIC key PEM, never the private signing key."
  }
}

variable "enabled" {
  description = "Disable distribution when retiring the demo; this is not an automatic spending cap."
  type        = bool
  default     = true
}

data "aws_caller_identity" "current" {}
data "aws_s3_bucket" "media" { bucket = var.media_bucket_name }

resource "aws_s3_bucket" "assets" {
  bucket        = "itube-cdn-assets-${data.aws_caller_identity.current.account_id}"
  force_destroy = false
}
resource "aws_s3_bucket_public_access_block" "assets" {
  bucket                  = aws_s3_bucket.assets.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
resource "aws_s3_bucket_ownership_controls" "assets" {
  bucket = aws_s3_bucket.assets.id
  rule { object_ownership = "BucketOwnerEnforced" }
}
resource "aws_s3_bucket_server_side_encryption_configuration" "assets" {
  bucket = aws_s3_bucket.assets.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

resource "aws_cloudfront_origin_access_control" "s3" {
  name                              = "itube-private-s3"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}
resource "aws_cloudfront_public_key" "media" {
  name_prefix = "itube-media-"
  encoded_key = var.signing_public_key_pem
  lifecycle { create_before_destroy = true }
}
resource "aws_cloudfront_key_group" "media" {
  name  = "itube-media"
  items = [aws_cloudfront_public_key.media.id]
}

# Query strings/cookies do not fragment these caches. CloudFront independently validates its signature parameters.
# min_ttl=0 respects no-store/private if an object is explicitly marked with those directives.
resource "aws_cloudfront_cache_policy" "objects" {
  for_each    = { assets = 31536000, media = 3600 }
  name        = "itube-${each.key}"
  min_ttl     = 0
  default_ttl = each.value
  max_ttl     = each.value
  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_brotli = each.key == "assets"
    enable_accept_encoding_gzip   = each.key == "assets"
    cookies_config { cookie_behavior = "none" }
    headers_config { header_behavior = "none" }
    query_strings_config { query_string_behavior = "none" }
  }
}

resource "aws_cloudfront_response_headers_policy" "objects" {
  name = "itube-cdn-objects"
  cors_config {
    access_control_allow_credentials = false
    access_control_allow_headers { items = ["Range"] }
    access_control_allow_methods { items = ["GET", "HEAD"] }
    access_control_allow_origins { items = ["https://itube.fun", "http://localhost:3000"] }
    access_control_expose_headers { items = ["ETag", "Content-Range", "Accept-Ranges", "Age", "X-Cache", "X-Amz-Cf-Pop"] }
    origin_override = true
  }
  security_headers_config {
    content_type_options { override = true }
    strict_transport_security {
      access_control_max_age_sec = 31536000
      override                   = true
    }
  }
  # Browsers re-check authorization with the edge; the edge's own object cache still uses the policy above.
  # Applied to media only below. A cached browser copy must not outlive the signed URL.
  custom_headers_config {
    items {
      header   = "Cache-Control"
      value    = "private, no-store"
      override = true
    }
  }
}

data "aws_cloudfront_response_headers_policy" "assets" {
  name = "Managed-CORS-with-preflight-and-SecurityHeadersPolicy"
}

resource "aws_cloudfront_distribution" "cdn" {
  enabled         = var.enabled
  is_ipv6_enabled = true
  comment         = "ITube: public hashed assets + signed private media, two S3 origins"
  # All global edge regions: keep demo traffic small rather than implying PriceClass_100 is worldwide edge coverage.
  price_class  = "PriceClass_All"
  http_version = "http2and3"

  origin {
    origin_id                = "media"
    domain_name              = data.aws_s3_bucket.media.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }
  origin {
    origin_id                = "assets"
    domain_name              = aws_s3_bucket.assets.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }
  default_cache_behavior {
    target_origin_id           = "media"
    viewer_protocol_policy     = "https-only"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    cache_policy_id            = aws_cloudfront_cache_policy.objects["media"].id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.objects.id
    trusted_key_groups         = [aws_cloudfront_key_group.media.id]
    compress                   = false
  }
  ordered_cache_behavior {
    path_pattern               = "/assets/*"
    target_origin_id           = "assets"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD", "OPTIONS"]
    cached_methods             = ["GET", "HEAD"]
    cache_policy_id            = aws_cloudfront_cache_policy.objects["assets"].id
    response_headers_policy_id = data.aws_cloudfront_response_headers_policy.assets.id
    compress                   = true
  }
  restrictions {
    geo_restriction { restriction_type = "none" }
  }
  viewer_certificate { cloudfront_default_certificate = true }
  # Never turn access denied into SPA HTML or cache authorization failures for minutes.
  custom_error_response {
    error_code            = 403
    error_caching_min_ttl = 0
  }
  custom_error_response {
    error_code            = 404
    error_caching_min_ttl = 0
  }
}

locals {
  origin_buckets = {
    assets = { name = aws_s3_bucket.assets.id, arn = aws_s3_bucket.assets.arn, prefix = "assets/*" }
    media  = { name = data.aws_s3_bucket.media.id, arn = data.aws_s3_bucket.media.arn, prefix = "videos/*" }
  }
}

# Sole owner of the media bucket policy. Cloudflare enable_media_cdn MUST remain false.
# OAC authorizes CloudFront to read S3; trusted_key_groups separately authorize viewers.
data "aws_iam_policy_document" "origin" {
  for_each = local.origin_buckets
  statement {
    sid       = "OnlyThisCloudFrontDistribution"
    actions   = ["s3:GetObject"]
    resources = ["${each.value.arn}/${each.value.prefix}"]
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.cdn.arn]
    }
  }
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [each.value.arn, "${each.value.arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}
resource "aws_s3_bucket_policy" "origin" {
  for_each = local.origin_buckets
  bucket   = each.value.name
  policy   = data.aws_iam_policy_document.origin[each.key].json
}

output "distribution_id" { value = aws_cloudfront_distribution.cdn.id }
output "cdn_base_url" { value = "https://${aws_cloudfront_distribution.cdn.domain_name}" }
output "assets_bucket" { value = aws_s3_bucket.assets.id }
output "signing_key_id" { value = aws_cloudfront_public_key.media.id }

# Additional policy on the existing GitHub role: upload only built assets; no delete/invalidation permission.
variable "deploy_role_name" {
  type        = string
  description = "Optional existing GitHub deployment role. Leave empty while EKS is shut down."
  default     = ""
}
resource "aws_iam_role_policy" "publish_assets" {
  count = var.deploy_role_name == "" ? 0 : 1
  name  = "itube-cdn-assets"
  role  = var.deploy_role_name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["s3:ListBucket"], Resource = aws_s3_bucket.assets.arn },
      { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject"], Resource = "${aws_s3_bucket.assets.arn}/assets/*" }
    ]
  })
}
