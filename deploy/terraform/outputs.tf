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
