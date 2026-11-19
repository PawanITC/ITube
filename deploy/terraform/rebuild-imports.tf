# Retained resources from the approved 2026-11-18 test-cluster rebuild.
# Declarative imports are idempotent; keep them as provenance for this shared state.
import {
  to = aws_ecr_lifecycle_policy.app["eks-lab/itube-backend"]
  id = "eks-lab/itube-backend"
}
import {
  to = aws_ecr_lifecycle_policy.app["eks-lab/itube-frontend"]
  id = "eks-lab/itube-frontend"
}
import {
  to = aws_s3_bucket.media
  id = "itube-media-160198385987"
}
import {
  to = aws_s3_bucket_public_access_block.media
  id = "itube-media-160198385987"
}
import {
  to = aws_s3_bucket_ownership_controls.media
  id = "itube-media-160198385987"
}
import {
  to = aws_s3_bucket_server_side_encryption_configuration.media
  id = "itube-media-160198385987"
}
import {
  to = aws_s3_bucket_lifecycle_configuration.media
  id = "itube-media-160198385987"
}
import {
  to = aws_iam_role.github_deploy
  id = "github-action-role"
}
import {
  to = aws_iam_role_policy.github_deploy
  id = "github-action-role:itube-deploy"
}
import {
  to = aws_iam_role.ebs_csi
  id = "eks-lab-dev-ebs-csi"
}
import {
  to = aws_iam_role_policy_attachment.ebs_csi
  id = "eks-lab-dev-ebs-csi/arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
}
import {
  to = aws_iam_role.backend_storage
  id = "eks-lab-dev-itube-backend-storage"
}
import {
  to = aws_iam_role_policy.backend_storage
  id = "eks-lab-dev-itube-backend-storage:itube-media-bucket"
}
import {
  to = module.eks.aws_cloudwatch_log_group.this[0]
  id = "/aws/eks/eks-lab-dev/cluster"
}
import {
  to = module.eks.module.kms.aws_kms_key.this[0]
  id = "60135587-218b-4873-8cee-03cf3bc88ffb"
}
import {
  to = module.eks.module.kms.aws_kms_alias.this["cluster"]
  id = "alias/eks/eks-lab-dev"
}
import {
  to = module.eks.aws_iam_role.this[0]
  id = "eks-lab-dev-cluster-8c0c884ea9c35fe9dddb662566"
}
import {
  to = module.eks.aws_iam_role_policy_attachment.this["AmazonEKSClusterPolicy"]
  id = "eks-lab-dev-cluster-8c0c884ea9c35fe9dddb662566/arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}
import {
  to = module.eks.aws_iam_policy.cluster_encryption[0]
  id = "arn:aws:iam::160198385987:policy/eks-lab-dev-cluster-ClusterEncryption21949c52c4ce6cd407324da66a"
}
import {
  to = module.eks.aws_iam_role_policy_attachment.cluster_encryption[0]
  id = "eks-lab-dev-cluster-8c0c884ea9c35fe9dddb662566/arn:aws:iam::160198385987:policy/eks-lab-dev-cluster-ClusterEncryption21949c52c4ce6cd407324da66a"
}
import {
  to = module.eks.module.eks_managed_node_group["default"].aws_iam_role.this[0]
  id = "default-eks-node-group-97219665e9bd6cd382b78e95b7"
}
import {
  to = module.eks.module.eks_managed_node_group["default"].aws_iam_role_policy_attachment.this["AmazonEKSWorkerNodePolicy"]
  id = "default-eks-node-group-97219665e9bd6cd382b78e95b7/arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy"
}
import {
  to = module.eks.module.eks_managed_node_group["default"].aws_iam_role_policy_attachment.this["AmazonEC2ContainerRegistryReadOnly"]
  id = "default-eks-node-group-97219665e9bd6cd382b78e95b7/arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
}
import {
  to = module.eks.module.eks_managed_node_group["default"].aws_iam_role_policy_attachment.this["AmazonEKS_CNI_Policy"]
  id = "default-eks-node-group-97219665e9bd6cd382b78e95b7/arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
}
