variable "cloudflare_account_id" {
  description = "Cloudflare account id (Dashboard -> any zone -> Overview, right-hand column)."
  type        = string
}

variable "zone_name" {
  description = "The domain already added to Cloudflare (its nameservers point at Cloudflare), e.g. example.com."
  type        = string
}

variable "app_subdomain" {
  description = "ITube is served at <app_subdomain>.<zone_name>. Use \"@\" for the bare domain."
  type        = string
  default     = "itube"
}

variable "namespace" {
  description = "Kubernetes namespace of the app (the tunnel forwards to frontend.<namespace>.svc)."
  type        = string
  default     = "itube"
}

variable "enable_media_cdn" {
  description = <<-EOT
    Serve public media (thumbnails; video files only if CDN_SERVE_VIDEO=true) from <media_subdomain>.<zone_name>,
    cached by Cloudflare in front of the S3 bucket. Needs media_bucket_allow_public_policy = true in deploy/terraform
    first. Read docs/CDN-AND-DNS.md about Cloudflare's terms for video before serving video files this way.
  EOT
  type        = bool
  default     = false
}

variable "media_subdomain" {
  description = "Public media hostname: <media_subdomain>.<zone_name>."
  type        = string
  default     = "media"
}

variable "media_bucket_name" {
  description = "The S3 media bucket (output media_bucket_name of deploy/terraform)."
  type        = string
  default     = "itube-media-160198385987"
}

variable "aws_region" {
  description = "Region of the media bucket."
  type        = string
  default     = "eu-west-2"
}

variable "login_rate_limit_per_10s" {
  description = "Sign-in attempts per IP per 10 seconds before Cloudflare blocks that IP for 10 seconds."
  type        = number
  default     = 20
}
