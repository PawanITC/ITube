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
  # At their minimum replica counts the pods in deploy/k8s request ~6.9 GiB of memory: data stores ~3.1 GiB (ScyllaDB
  # 1.5 GiB, Kafka 768 MiB, Postgres 512 MiB + shard 256 MiB, Redis) and services ~3.8 GiB (2x identity, 2x catalog,
  # 2x engagement, 2x gateway, payments, transcode-worker, frontend, 2x cloudflared). 2 x t3.large (~7 GiB allocatable
  # each) leaves ~5 GiB for the HorizontalPodAutoscalers to add pods under load; raise node_count (or add the
  # cluster autoscaler) for more. The EBS-backed StatefulSets are pinned to their volume's AZ.
  description = "Instance type of the managed node group (8 GiB t3.large: room for Postgres + ScyllaDB + Kafka)."
  type        = string
  default     = "t3.large"
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
  # itube-backend is the pre-microservices image; kept so its repository (and old images) isn't destroyed. Remove it
  # once nothing needs those images (ECR refuses to delete a repository that still has images).
  default = [
    "eks-lab/itube-backend",
    "eks-lab/itube-frontend",
    "eks-lab/itube-gateway",
    "eks-lab/itube-identity",
    "eks-lab/itube-catalog",
    "eks-lab/itube-engagement",
    "eks-lab/itube-payments",
    "eks-lab/itube-transcode-worker",
  ]
}

variable "ecr_keep_images" {
  description = "Number of most recent images kept per ECR repository; older ones are expired."
  type        = number
  default     = 30
}

variable "backend_service_account" {
  description = "Kubernetes service account of catalog-service and transcode-worker; the media bucket role is bound to it through EKS Pod Identity."
  type        = string
  default     = "backend"
}

variable "media_bucket_name" {
  description = "S3 bucket for video files, renditions and thumbnails. null = itube-media-<account id>."
  type        = string
  default     = null
}

variable "media_bucket_force_destroy" {
  description = "Let `terraform destroy` delete the media bucket even when it still holds objects (dev only)."
  type        = bool
  default     = false
}

variable "media_bucket_allow_public_policy" {
  description = "Allow a bucket policy granting public reads (needed for the Cloudflare media CDN, deploy/cloudflare enable_media_cdn). The policy itself only admits Cloudflare IPs and objects tagged public=true."
  type        = bool
  default     = false
}

variable "media_bucket_cors_origins" {
  description = "Origins allowed to upload parts straight to the media bucket (the app URL, e.g. https://itube.example.com)."
  type        = list(string)
  default     = ["https://itube.fun", "http://localhost:3000"]
}
