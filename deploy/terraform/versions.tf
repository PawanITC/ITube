terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.38"
    }
  }

  # State is local by default. To share it (recommended once more than one person runs this),
  # create an S3 bucket and uncomment:
  # backend "s3" {
  #   bucket       = "<your-state-bucket>"
  #   key          = "itube/eks-dev/terraform.tfstate"
  #   region       = "eu-west-2"
  #   encrypt      = true
  #   use_lockfile = true
  # }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      project     = "itube"
      environment = "dev"
      managed-by  = "terraform"
    }
  }
}

# Authenticates as whoever runs Terraform; the EKS module gives that identity cluster admin.
provider "kubernetes" {
  host                   = module.eks.cluster_endpoint
  cluster_ca_certificate = base64decode(module.eks.cluster_certificate_authority_data)

  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args        = ["eks", "get-token", "--cluster-name", module.eks.cluster_name, "--region", var.region]
  }
}
