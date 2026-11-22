# Runs a small CDN acceptance test even while EKS is destroyed. No permission to create infrastructure.
data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}
data "aws_iam_policy_document" "demo_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:PawanITC/ITube:environment:dev"]
    }
  }
}
resource "aws_iam_role" "demo" {
  name               = "itube-cdn-demo"
  assume_role_policy = data.aws_iam_policy_document.demo_trust.json
}
resource "aws_iam_role_policy" "demo" {
  name = "cdn-demo-objects-only"
  role = aws_iam_role.demo.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = ["s3:PutObject", "s3:DeleteObject"]
      Resource = [
        "${aws_s3_bucket.assets.arn}/assets/cdn-demo/*",
        "${data.aws_s3_bucket.media.arn}/videos/cdn-demo/*"
      ]
    }]
  })
}
output "demo_role_arn" { value = aws_iam_role.demo.arn }
