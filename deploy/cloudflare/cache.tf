# What Cloudflare may cache at the edge. By default it only caches static file types (images, JS, CSS...); API
# responses are never cached unless a rule says so, and responses that set a cookie are never cached at all.
# Only content that is the same for every visitor is listed here; anything per-user or paid stays uncached
# (ITube marks those responses Cache-Control: private, no-store).
resource "cloudflare_ruleset" "cache" {
  zone_id     = local.zone_id
  name        = "ITube cache rules"
  description = "Edge caching for ITube (managed by deploy/cloudflare)"
  kind        = "zone"
  phase       = "http_request_cache_settings"

  rules = concat([
    {
      description = "Built frontend assets (hashed file names): cache as long as the origin says (1 year, immutable)"
      expression  = "(http.host eq \"${local.app_hostname}\" and starts_with(http.request.uri.path, \"/assets/\"))"
      action      = "set_cache_settings"
      action_parameters = {
        cache    = true
        edge_ttl = { mode = "respect_origin" }
      }
      enabled = true
    },
    {
      # The home feed is the same for everyone and catalog-service marks it public, max-age=10. At peak, almost
      # every home page load is then answered by the edge, and ITube sees about one request per edge location
      # every 10 seconds.
      description = "Home feed GET /api/videos: cache for the 10 s the origin allows"
      expression  = "(http.host eq \"${local.app_hostname}\" and http.request.method eq \"GET\" and http.request.uri.path eq \"/api/videos\")"
      action      = "set_cache_settings"
      action_parameters = {
        cache    = true
        edge_ttl = { mode = "respect_origin" }
      }
      enabled = true
    },
    {
      description = "Thumbnail redirects: cache the redirect for the 10 min the origin allows"
      expression  = "(http.host eq \"${local.app_hostname}\" and http.request.method eq \"GET\" and starts_with(http.request.uri.path, \"/api/videos/\") and ends_with(http.request.uri.path, \"/thumbnail\"))"
      action      = "set_cache_settings"
      action_parameters = {
        cache    = true
        edge_ttl = { mode = "respect_origin" }
      }
      enabled = true
    },
    ], var.enable_media_cdn ? [
    {
      # Media object keys contain a random id and are never overwritten, so a cached copy can never go stale
      description = "Public media from S3: cache 30 days at the edge, 1 day in browsers"
      expression  = "(http.host eq \"${local.media_hostname}\")"
      action      = "set_cache_settings"
      action_parameters = {
        cache = true
        edge_ttl = {
          mode    = "override_origin"
          default = 2592000
        }
        browser_ttl = {
          mode    = "override_origin"
          default = 86400
        }
      }
      enabled = true
    },
  ] : [])
}
