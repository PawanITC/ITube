# Cloudflare Tunnel: Cloudflare's edge reaches ITube through outbound connections from cloudflared (deploy/k8s-cloudflare).
resource "cloudflare_zero_trust_tunnel_cloudflared" "itube" {
  account_id = var.cloudflare_account_id
  name       = "itube-${var.namespace}"
  # Routes are managed here (below), not in a config file inside the pod
  config_src = "cloudflare"
}

resource "cloudflare_zero_trust_tunnel_cloudflared_config" "itube" {
  account_id = var.cloudflare_account_id
  tunnel_id  = cloudflare_zero_trust_tunnel_cloudflared.itube.id

  config = {
    ingress = [
      {
        hostname = local.app_hostname
        # The frontend's nginx serves the React app and proxies /api/ to the gateway
        service = "http://frontend.${var.namespace}.svc.cluster.local:80"
        origin_request = {
          connect_timeout = 10
          # Keep connections to the frontend open between requests
          keep_alive_connections = 100
        }
      },
      {
        # Required catch-all: anything else is refused
        service = "http_status:404"
      },
    ]
  }
}

# Token for the cloudflared pods (secret cloudflare-tunnel in Kubernetes, or GitHub secret CLOUDFLARE_TUNNEL_TOKEN)
data "cloudflare_zero_trust_tunnel_cloudflared_token" "itube" {
  account_id = var.cloudflare_account_id
  tunnel_id  = cloudflare_zero_trust_tunnel_cloudflared.itube.id
}

# DNS: the app hostname points at the tunnel. Proxied = traffic goes through Cloudflare (CDN, WAF, TLS), and the
# record resolves to Cloudflare's anycast IPs, so visitors connect to the nearest of Cloudflare's data centres.
resource "cloudflare_dns_record" "app" {
  zone_id = local.zone_id
  name    = local.app_hostname
  type    = "CNAME"
  content = "${cloudflare_zero_trust_tunnel_cloudflared.itube.id}.cfargotunnel.com"
  proxied = true
  ttl     = 1 # "automatic", required for proxied records
  comment = "ITube app through Cloudflare Tunnel (managed by deploy/cloudflare)"
}
