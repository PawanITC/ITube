# Settings live in these defaults: *.tfvars files are git-ignored in this repo, so CI would never see them.

variable "region" {
  description = "AWS region."
  type        = string
  default     = "eu-west-2"
}

variable "environment" {
  description = "Environment name, used in tags and resource names."
  type        = string
  default     = "dev"
}
