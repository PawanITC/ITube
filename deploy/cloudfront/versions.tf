terraform {
  required_version = ">= 1.10"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
  backend "s3" {
    bucket       = "itube-tfstate-160198385987"
    key          = "itube/cloudfront/terraform.tfstate"
    region       = "eu-west-2"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region              = "eu-west-2"
  allowed_account_ids = ["160198385987"]
  default_tags {
    tags = { project = "itube", environment = "dev", managed-by = "terraform", component = "cdn" }
  }
}
