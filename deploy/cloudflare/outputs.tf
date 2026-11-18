output "app_url" {
  description = "Where ITube is served. Put it in FRONTEND_BASE_URL (deploy/k8s/configmap.yaml) for Stripe redirects."
  value       = "https://${local.app_hostname}"
}

output "media_base_url" {
  description = "Value for CDN_MEDIA_BASE_URL (deploy/k8s/configmap.yaml) when enable_media_cdn = true."
  value       = var.enable_media_cdn ? "https://${local.media_hostname}" : ""
}

output "tunnel_id" {
  value = cloudflare_zero_trust_tunnel_cloudflared.itube.id
}

output "tunnel_token" {
  description = "Set as the GitHub secret CLOUDFLARE_TUNNEL_TOKEN (environment dev): terraform output -raw tunnel_token"
  value       = data.cloudflare_zero_trust_tunnel_cloudflared_token.itube.token
  sensitive   = true
}
