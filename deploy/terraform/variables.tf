variable "region" {
  description = "AWS region. Must match AWS_REGION in .github/workflows/deploy-eks.yml."
  type        = string
  default     = "eu-west-2"
}

variable "cluster_name" {
  description = "EKS cluster name. Must match EKS_CLUSTER in .github/workflows/deploy-eks.yml."
  type        = string
  default     = "eks-lab-dev"
}

variable "kubernetes_version" {
  # Must be a known value: the EKS module's node group can't plan with null (version unknown until apply).
  # 1.36 is in EKS standard support until 2027-08-02; upgrade one minor version at a time.
  description = "EKS Kubernetes version (control plane and nodes)."
  type        = string
  default     = "1.36"
  nullable    = false
}

variable "vpc_cidr" {
  description = "CIDR block of the cluster VPC."
  type        = string
  default     = "10.40.0.0/16"
}

variable "node_instance_type" {
  description = "Instance type of the managed node group (deploy/README.md budgets for 2 x t3.medium)."
  type        = string
  default     = "t3.medium"
}

variable "node_count" {
  description = "Desired and minimum number of nodes."
  type        = number
  default     = 2
}

variable "node_disk_size" {
  description = "Root volume size of each node in GiB (images + up to 3Gi /tmp for backend uploads and FFmpeg)."
  type        = number
  default     = 50
}

variable "namespace" {
  description = "Kubernetes namespace the app is deployed to. Must match K8S_NAMESPACE in the workflow."
  type        = string
  default     = "itube"
}

variable "github_repository" {
  description = "GitHub repository (owner/name) allowed to assume the deploy role."
  type        = string
  default     = "PawanITC/ITube"
}

variable "github_oidc_subjects" {
  description = "Exact OIDC 'sub' claims allowed to assume the deploy role (the workflow job uses environment 'dev')."
  type        = list(string)
  default = [
    "repo:PawanITC/ITube:environment:dev",
    "repo:PawanITC@239576472/ITube@1418608547:environment:dev",
  ]
}

variable "deploy_role_name" {
  description = "Name of the IAM role GitHub Actions assumes (the workflow variable AWS_DEPLOY_ROLE_ARN points to it)."
  type        = string
  default     = "github-action-role"
}

variable "ecr_repositories" {
  description = "ECR repositories the workflow pushes to."
  type        = list(string)
  default     = ["eks-lab/itube-backend", "eks-lab/itube-frontend"]
}

variable "ecr_keep_images" {
  description = "Number of most recent images kept per ECR repository; older ones are expired."
  type        = number
  default     = 30
}
