# Object storage for video sources, renditions and thumbnails (docs/DATA-ARCHITECTURE.md, "media" domain).
#
# The backend (deploy/k8s/backend.yaml) reaches this bucket through EKS Pod Identity (no access keys): its pod runs
# as the service account var.backend_service_account, which the association at the bottom maps to the IAM role.
# Its STORAGE_BUCKET env value must equal the bucket name (output media_bucket_name).
# SeaweedFS is only the local stand-in (docker-compose.yml, deploy/k8s-local).

data "aws_caller_identity" "current" {}

locals {
  # Bucket names are global: suffix the account id so the default name is free
  media_bucket_name = coalesce(var.media_bucket_name, "itube-media-${data.aws_caller_identity.current.account_id}")
}

resource "aws_s3_bucket" "media" {
  bucket = local.media_bucket_name
  # Dev: let `terraform destroy` delete a bucket that still holds videos
  force_destroy = var.media_bucket_force_destroy
}

resource "aws_s3_bucket_public_access_block" "media" {
  bucket = aws_s3_bucket.media.id

  # Browsers only ever get pre-signed URLs, never public objects
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "media" {
  bucket = aws_s3_bucket.media.id

  rule {
    object_ownership = "BucketOwnerEnforced" # ACLs off
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "media" {
  bucket = aws_s3_bucket.media.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "media" {
  bucket = aws_s3_bucket.media.id

  rule {
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"
    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# IAM role the backend pod assumes through EKS Pod Identity (the eks-pod-identity-agent add-on is in eks.tf)
data "aws_iam_policy_document" "backend_storage_assume" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]

    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "backend_storage" {
  name               = "${var.cluster_name}-itube-backend-storage"
  description        = "ITube backend (${var.namespace}/${var.backend_service_account}) access to the media bucket"
  assume_role_policy = data.aws_iam_policy_document.backend_storage_assume.json
}

data "aws_iam_policy_document" "backend_storage" {
  # HeadBucket at startup; also makes HeadObject on a missing key return 404 (which the backend expects) instead of 403
  statement {
    sid       = "ListBucket"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.media.arn]
  }

  # Upload, ranged reads, pre-signed GETs (signed with this role) and deletes (video.deleted cleanup)
  statement {
    sid = "ReadWriteObjects"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:AbortMultipartUpload",
    ]
    resources = ["${aws_s3_bucket.media.arn}/*"]
  }
}

resource "aws_iam_role_policy" "backend_storage" {
  name   = "itube-media-bucket"
  role   = aws_iam_role.backend_storage.id
  policy = data.aws_iam_policy_document.backend_storage.json
}

# Binds the role to the service account; the service account itself belongs to the app manifests
resource "aws_eks_pod_identity_association" "backend_storage" {
  cluster_name    = module.eks.cluster_name
  namespace       = var.namespace
  service_account = var.backend_service_account
  role_arn        = aws_iam_role.backend_storage.arn
}
