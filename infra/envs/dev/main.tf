# ITube dev infrastructure. Starts empty on purpose: the first run only proves that GitHub Actions can log
# in and use the state bucket.
#
# The EKS cluster, ECR repositories and deploy role are currently owned by the separate team infra repository.
# Don't recreate them here; two Terraform configurations managing the same resource overwrite each other.
# Add new resources below, or import existing ones (`import` blocks) once that repository hands them over.
# Name IAM roles and policies itube-*: that's all the apply role may manage.

data "aws_caller_identity" "current" {}
