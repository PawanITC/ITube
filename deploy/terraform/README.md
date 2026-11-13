# ITube dev infrastructure (Terraform)

Creates everything the deploy workflow [`deploy-eks.yml`](../../.github/workflows/deploy-eks.yml) needs in
account `160198385987`, region `eu-west-2`:

| File | What it creates |
|---|---|
| [`vpc.tf`](vpc.tf) | VPC across 2 AZs, private subnets for the nodes, public subnets, a single NAT gateway |
| [`eks.tf`](eks.tf) | EKS cluster `eks-lab-dev`, add-ons (VPC CNI, CoreDNS, kube-proxy, Pod Identity agent, EBS CSI driver), 2 x t3.medium managed nodes, access entry giving the deploy role `AmazonEKSEditPolicy` in namespace `itube` |
| [`github.tf`](github.tf) | GitHub OIDC identity provider and the deploy role `github-action-role` (trust policy + ECR push / `eks:DescribeCluster` permissions) |
| [`ecr.tf`](ecr.tf) | ECR repositories `eks-lab/itube-backend` and `eks-lab/itube-frontend` (immutable tags, scan on push, keep last 30 images) |
| [`kubernetes.tf`](kubernetes.tf) | StorageClass `gp3` (encrypted, EBS CSI) and namespace `itube` (Pod Security `restricted`) |
| [`imports.tf`](imports.tf) | Adopts the OIDC provider, deploy role and ECR repositories that were created by hand |

The app itself (`deploy/k8s`) is still deployed by the workflow, not by Terraform.

## Prerequisites

- Terraform >= 1.10, AWS CLI v2 (the Kubernetes provider calls `aws eks get-token`)
- AWS credentials for an admin identity in account `160198385987`. That identity becomes cluster admin.

## Apply

```bash
cd deploy/terraform
terraform init
terraform plan -out tfplan
terraform apply tfplan
```

The first apply takes about 15-20 minutes. Check that the plan **imports** (not creates) the four resources in
`imports.tf`. If one of them does not exist in the account, remove its block from `imports.tf`.

After the first successful apply:

1. Delete `imports.tf` (the resources are now in the state).
2. Delete the hand-made inline policy on `github-action-role` in the IAM console; Terraform's inline policy
   `itube-deploy` replaces it.
3. Point kubectl at the cluster: run the `kubeconfig_command` output.
4. Check that the GitHub variable `AWS_DEPLOY_ROLE_ARN` (environment `dev`) equals the `deploy_role_arn` output,
   then re-run the deploy workflow.

## Changing things

Defaults are in [`variables.tf`](variables.tf). Override them in a `terraform.tfvars` file (git-ignored), e.g.:

```hcl
deploy_role_name   = "eks-lab-dev-app-itube"
kubernetes_version = "1.37"
```

Renaming the deploy role replaces it, so update `AWS_DEPLOY_ROLE_ARN` in GitHub afterwards.

## State

State is stored locally in `terraform.tfstate` (git-ignored). Losing it means Terraform no longer knows about the
resources, so before more than one person runs this, move it to S3 with the commented `backend "s3"` block in
[`versions.tf`](versions.tf).

## Cost and teardown

Roughly $190/month while it exists: EKS control plane ~$73, 2 x t3.medium ~$70, NAT gateway ~$35 + data,
EBS ~$10.

```bash
terraform destroy
```

**This deletes the cluster and the Postgres/Kafka volumes, i.e. all ITube data** (back up first:
`kubectl -n itube exec postgres-0 -- pg_dump -U itube itube > itube.sql`). It also deletes the deploy role and the
ECR repositories with their images; ECR refuses to delete a repository that still has images, so empty them first.
