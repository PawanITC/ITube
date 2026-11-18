# Edge protection on top of Cloudflare's always-on DDoS mitigation (free on every plan).
#
# Sign-in brute force is stopped at the edge, before it reaches the cluster. The API gateway has its own, stricter
# per-IP limits as a second layer (gateway/templates/default.conf.template). The free plan allows one rate limiting
# rule, with a 10 second window.
resource "cloudflare_ruleset" "rate_limit" {
  zone_id     = local.zone_id
  name        = "ITube rate limits"
  description = "Sign-in brute-force protection (managed by deploy/cloudflare)"
  kind        = "zone"
  phase       = "http_ratelimit"

  rules = [{
    description = "Sign-in and registration: at most ${var.login_rate_limit_per_10s} per IP per 10 s"
    expression  = "(http.host eq \"${local.app_hostname}\" and http.request.method eq \"POST\" and http.request.uri.path in {\"/api/auth/login\" \"/api/auth/register\"})"
    action      = "block"
    ratelimit = {
      characteristics     = ["ip.src", "cf.colo.id"]
      period              = 10
      requests_per_period = var.login_rate_limit_per_10s
      mitigation_timeout  = 10
    }
    enabled = true
  }]
}
