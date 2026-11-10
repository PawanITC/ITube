variable "region" {
  description = "AWS region for the state bucket (and the default for everything else)."
  type        = string
  default     = "eu-west-2"
}

variable "state_bucket_name" {
  description = "Terraform state bucket. Empty = itube-tfstate-<account id>. Must match infra/envs/*/backend.tf."
  type        = string
  default     = ""
}

variable "create_oidc_provider" {
  description = "Create the GitHub OIDC provider. Leave false when the account already has one (it does if the EKS deploy role exists)."
  type        = bool
  default     = false
}

variable "github_subject_prefix" {
  description = <<-EOT
    Start of the OIDC `sub` claim GitHub sends for this repository. The default format is repo:OWNER/REPO.
    PawanITC uses the ID-based format (repo:PawanITC@<org id>/ITube@<repo id>), as the EKS deploy role shows.
  EOT
  type        = string
  default     = "repo:PawanITC@239576472/ITube@1418608547"
}

variable "apply_environment" {
  description = "GitHub environment whose jobs may assume the apply role. Give it required reviewers."
  type        = string
  default     = "dev"
}

variable "apply_role_policy_arns" {
  description = "Managed policies for the apply role. Narrow these once you know what Terraform manages."
  type        = list(string)
  default     = ["arn:aws:iam::aws:policy/PowerUserAccess"]
}
