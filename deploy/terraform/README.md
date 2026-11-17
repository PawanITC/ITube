# ITube dev infrastructure (Terraform)

Creates everything the deploy workflow [`deploy-eks.yml`](../../.github/workflows/deploy-eks.yml) needs in
account `160198385987`, region `eu-west-2`:

| File | What it creates |
|---|---|
| [`vpc.tf`](vpc.tf) | VPC across 2 AZs, private subnets for the nodes, public subnets, a single NAT gateway |
| [`eks.tf`](eks.tf) | EKS cluster `eks-lab-dev`, add-ons (VPC CNI, CoreDNS, kube-proxy, Pod Identity agent, EBS CSI driver), 2 x t3.large managed nodes, access entry giving the deploy role `AmazonEKSEditPolicy` in namespace `itube` |
| [`github.tf`](github.tf) | GitHub OIDC identity provider and the deploy role `github-action-role` (trust policy + ECR push / `eks:DescribeCluster` permissions) |
| [`ecr.tf`](ecr.tf) | ECR repositories `eks-lab/itube-backend` and `eks-lab/itube-frontend` (immutable tags, scan on push, keep last 30 images) |
| [`kubernetes.tf`](kubernetes.tf) | StorageClass `gp3` (encrypted, EBS CSI) and namespace `itube` (Pod Security: enforce `baseline`, warn/audit `restricted`) |
| [`storage.tf`](storage.tf) | S3 media bucket `itube-media-<account id>` (private, encrypted) and the backend's Pod Identity role for it |
| [`imports.tf`](imports.tf) | Adopts the OIDC provider, deploy role and ECR repositories that were created by hand |

The app itself (`deploy/k8s`) is still deployed by the workflow, not by Terraform.

## Data stores

The backend uses three stores (see [`docs/DATA-ARCHITECTURE.md`](../../docs/DATA-ARCHITECTURE.md)):

| Store | Where | Size | Holds |
|---|---|---|---|
| PostgreSQL 17 | StatefulSet `deploy/k8s/postgres.yaml` | 20Gi gp3 | schemas `identity`, `catalog`, `payments` |
| ScyllaDB 6.2 (1 node, developer mode) | StatefulSet `deploy/k8s/scylla.yaml` | 20Gi gp3 | keyspace `itube_engagement` (history, likes, comments) |
| Amazon S3 | bucket `itube-media-<account id>` ([`storage.tf`](storage.tf)) | by usage | video sources, renditions, thumbnails |

Kafka (5Gi gp3) has the third volume. ScyllaDB runs as root, which is why the namespace enforces Pod Security
`baseline` instead of `restricted`.

The backend pod runs as the ServiceAccount `backend` (variable `backend_service_account`); EKS Pod Identity gives
it the `backend_storage_role_arn` role, so no S3 keys exist anywhere. `STORAGE_BUCKET` in
`deploy/k8s/backend.yaml` must equal the `media_bucket_name` output. Apply Terraform **before** deploying the
backend, or it fails at startup because it can't reach the bucket.

SeaweedFS is only the local stand-in for S3 (`docker-compose.yml`, `tf_docker`, `deploy/k8s-local`).

### Moving off an in-cluster SeaweedFS

If an earlier deploy already ran SeaweedFS on this cluster, `kubectl apply` leaves it running (it doesn't prune),
and video keys in Postgres point at objects stored in it. Copy them to S3 (same keys), then remove it:

```bash
kubectl -n itube port-forward statefulset/seaweedfs 8333:8333   # in a second terminal
AWS_ACCESS_KEY_ID=itube AWS_SECRET_ACCESS_KEY=<storage-secret-key from itube-secrets> \
  aws s3 sync s3://itube-media ./itube-media --endpoint-url http://localhost:8333
aws s3 sync ./itube-media "s3://$(terraform output -raw media_bucket_name)"
kubectl -n itube delete statefulset/seaweedfs service/seaweedfs pvc/data-seaweedfs-0
```

Skip the copy if the dev data doesn't matter; videos whose files are missing just fail to play.

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

Roughly $265/month while it exists: EKS control plane ~$73, 2 x t3.large ~$140, NAT gateway ~$35 + data,
EBS ~$14 (2 x 50Gi node disks + 45Gi of PVCs), S3 by usage (~$0.024/GB-month in eu-west-2).

```bash
terraform destroy
```

**This deletes the cluster, the Postgres, ScyllaDB and Kafka volumes and the media bucket
(`media_bucket_force_destroy = true`), i.e. all ITube data.** Back up first:
`kubectl -n itube exec postgres-0 -- pg_dump -U itube itube > itube.sql` for Postgres and
`kubectl -n itube exec scylla-0 -- nodetool snapshot itube_engagement` for ScyllaDB (then copy the snapshot out). It also deletes the deploy role and the
ECR repositories with their images; ECR refuses to delete a repository that still has images, so empty them first.
