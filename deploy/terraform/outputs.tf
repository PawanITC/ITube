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
