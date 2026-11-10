terraform {
  required_version = ">= 1.10" # use_lockfile in the S3 backend

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Created by infra/bootstrap. Bucket names can't use variables here; change it if bootstrap printed another.
  backend "s3" {
    bucket       = "itube-tfstate-160198385987"
    key          = "dev/terraform.tfstate"
    region       = "eu-west-2"
    use_lockfile = true
    encrypt      = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "itube"
      Environment = var.environment
      ManagedBy   = "terraform"
      Repository  = "PawanITC/ITube"
    }
  }
}
