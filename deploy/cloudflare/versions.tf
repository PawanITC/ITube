terraform {
  required_version = ">= 1.10"

  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.28.0"
    }
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  backend "s3" {
    bucket       = "itube-tfstate-160198385987"
    key          = "itube/cloudflare/terraform.tfstate"
    region       = "eu-west-2"
    encrypt      = true
    use_lockfile = true
  }
}

# Reads CLOUDFLARE_API_TOKEN from the environment (never put the token in a .tf or .tfvars file).
# Token permissions: Zone - DNS:Edit, Zone Settings:Edit, Cache Rules:Edit, Config Rules:Edit, Origin Rules:Edit
# (Transform Rules), Zone WAF:Edit; Account - Cloudflare Tunnel:Edit. Scope it to the one zone.
provider "cloudflare" {}

# Only used for the media bucket's policy (when enable_media_cdn = true)
provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      project     = "itube"
      environment = "dev"
      managed-by  = "terraform"
    }
  }
}
