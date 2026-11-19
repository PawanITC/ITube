# ITube's public edge on Cloudflare (free plan):
#
#   visitor --HTTPS--> Cloudflare edge (DNS, TLS, cache, WAF, DDoS protection, rate limit)
#                         |  app:   <app_subdomain>.<zone>   --Cloudflare Tunnel--> cloudflared pods -> frontend -> gateway
#                         |  media: <media_subdomain>.<zone> --HTTPS--> S3 bucket (only objects tagged public=true)
#
# The tunnel means the EKS cluster needs no public load balancer and no open inbound port: cloudflared dials out.

data "cloudflare_zone" "this" {
  filter = {
    name = var.zone_name
  }
}

locals {
  zone_id        = data.cloudflare_zone.this.zone_id
  app_hostname   = var.app_subdomain == "@" ? var.zone_name : "${var.app_subdomain}.${var.zone_name}"
  media_hostname = "${var.media_subdomain}.${var.zone_name}"
  s3_hostname    = "${var.media_bucket_name}.s3.${var.aws_region}.amazonaws.com"
}

# Smart Tiered Cache (free): a cache miss at one of Cloudflare's ~300 edge locations is first asked of a regional
# "upper tier" data centre instead of the origin. This is the CDN's "origin shield": a popular file is fetched from
# S3 (or ITube) once per region, not once per edge location, which protects the origin from thundering herds.
resource "cloudflare_tiered_cache" "smart" {
  zone_id = local.zone_id
  value   = "on"
}

# Keep the API's short Cache-Control lifetime; Cloudflare's default browser TTL is four hours.
resource "cloudflare_zone_setting" "browser_cache_ttl" {
  zone_id    = local.zone_id
  setting_id = "browser_cache_ttl"
  value      = 0 # Respect Existing Headers
}
