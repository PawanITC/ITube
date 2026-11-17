# The two ECR repositories were created by hand (2026-11-12), before Terraform. Adopt them instead of creating
# them: without this, apply fails with RepositoryAlreadyExistsException and never creates the deploy role's
# policy (it needs the repository ARNs), so GitHub Actions gets "not authorized to perform ecr:GetAuthorizationToken".
# Delete this file after the first successful apply; the repositories are then in the state.

import {
  to = aws_ecr_repository.app["eks-lab/itube-backend"]
  id = "eks-lab/itube-backend"
}

import {
  to = aws_ecr_repository.app["eks-lab/itube-frontend"]
  id = "eks-lab/itube-frontend"
}
