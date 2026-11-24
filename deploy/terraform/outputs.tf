output "cluster_name" {
  value = module.eks.cluster_name
}

output "deploy_role_arn" {
  description = "Value for the GitHub variable AWS_DEPLOY_ROLE_ARN (environment dev)."
  value       = aws_iam_role.github_deploy.arn
}

output "ecr_repository_urls" {
  value = { for name, repo in aws_ecr_repository.app : name => repo.repository_url }
}

output "kubeconfig_command" {
  value = "aws eks update-kubeconfig --region ${var.region} --name ${module.eks.cluster_name}"
}

output "media_bucket_name" {
  description = "S3 media bucket; must equal STORAGE_BUCKET in deploy/k8s/backend.yaml."
  value       = aws_s3_bucket.media.bucket
}

output "backend_storage_role_arn" {
  description = "IAM role the backend service account assumes (EKS Pod Identity) to reach the media bucket."
  value       = aws_iam_role.backend_storage.arn
}

output "cognito_user_pool_id" {
  description = "COGNITO_USER_POOL_ID for identity-service (also in the ConfigMap itube-cognito)."
  value       = aws_cognito_user_pool.itube.id
}

output "cognito_client_id" {
  description = "COGNITO_CLIENT_ID for identity-service (public app client, PKCE)."
  value       = aws_cognito_user_pool_client.web.id
}

output "cognito_domain" {
  description = "COGNITO_DOMAIN for identity-service: the hosted login page."
  value       = "https://${aws_cognito_user_pool_domain.itube.domain}.auth.${var.region}.amazoncognito.com"
}

output "otlp_endpoint" {
  description = "OTLP/HTTP base URL inside the cluster; deploy/k8s/configmap.yaml appends /v1/metrics, /v1/traces and /v1/logs."
  value       = "http://${kubernetes_service_v1.lgtm.metadata[0].name}.${kubernetes_namespace_v1.observability.metadata[0].name}.svc.cluster.local:4318"
}

output "grafana_port_forward_command" {
  description = "Opens Grafana on http://localhost:3080 (log in with user admin and the grafana_admin_password output)."
  value       = "kubectl -n ${kubernetes_namespace_v1.observability.metadata[0].name} port-forward svc/${kubernetes_service_v1.lgtm.metadata[0].name} 3080:3000"
}

output "grafana_admin_password" {
  description = "Grafana admin password: terraform output -raw grafana_admin_password"
  value       = random_password.grafana_admin.result
  sensitive   = true
}
