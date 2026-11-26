# AWS infrastructure with Terraform and GitHub Actions

| Folder | What it is | Who runs it |
|---|---|---|
| [`bootstrap/`](bootstrap) | State bucket, GitHub OIDC provider, plan and apply roles | You, once, with admin credentials |
| [`envs/dev/`](envs/dev) | The dev infrastructure itself | GitHub Actions ([`terraform.yml`](../.github/workflows/terraform.yml)) |
| `modules/` | Reusable pieces, once there are any | Used from `envs/*` |

No AWS keys are stored in GitHub. The workflow logs in with GitHub OIDC:

- **Pull request** touching `infra/envs`, `infra/modules` or the workflow: `fmt`, `validate` and `plan` with the
  read-only role `itube-terraform-plan`. The plan is posted as a PR comment (updated on every push).
- **Merge to `master`** (or *Run workflow*): `plan` + `apply` with the role `itube-terraform-apply`, which only the
  GitHub environment `dev` can use (the same environment the EKS deploy uses). If `dev` has required reviewers,
  every apply waits for an approval; without them, merging applies straight away.
- State is in S3 with native locking (`use_lockfile`), so no DynamoDB table. Runs never overlap.

> **Before adding resources:** the EKS cluster, ECR repositories and the EKS deploy role in account
> `160198385987` belong to the separate team infra repository. Don't define them here as well; two Terraform
> configurations managing the same resource overwrite each other. `envs/dev/msk.tf` reads the existing EKS,
> VPC, private subnets and node security group and owns only the new MSK resources and dedicated client roles.

## 1. Bootstrap (once)

Needs Terraform 1.10+ and the AWS CLI, logged in as an admin of the account (for example `aws sso login`, or
`aws configure` with temporary keys you delete afterwards).

```bash
cd infra/bootstrap
terraform init
terraform apply
```

Check these defaults in [`bootstrap/variables.tf`](bootstrap/variables.tf) first, and override them with `-var` if needed:

| Variable | Default | Change it when |
|---|---|---|
| `region` | `eu-west-2` | you use another region |
| `state_bucket_name` | `itube-tfstate-<account id>` | the name is taken |
| `create_oidc_provider` | `false` | the account has no GitHub OIDC provider yet (IAM -> Identity providers) |
| `github_subject_prefix` | `repo:PawanITC@239576472/ITube@1418608547` | the org uses the default `repo:OWNER/REPO` format, or the repo moves |
| `apply_environment` | `dev` | you name the GitHub environment differently |
| `apply_role_policy_arns` | `PowerUserAccess` | you want it narrower (recommended once you know what's managed) |

The apply role also gets IAM rights limited to roles, policies and instance profiles named `itube-*` (needed for
EKS node and service roles) and can never change the two Terraform roles themselves.

Bootstrap keeps its state in `bootstrap/terraform.tfstate`, which is git-ignored. Keep that file somewhere safe
(it's needed to change or remove the bootstrap later).

## 2. GitHub settings (once)

| Where | Name | Value |
|---|---|---|
| Settings -> Environments | `dev` (already exists) | Limit deployment branches to `master`. Required reviewers are optional: they also gate the EKS deploys, which use the same environment |
| Repository variable | `AWS_TERRAFORM_PLAN_ROLE_ARN` | output `plan_role_arn` |
| Environment `dev` variable | `AWS_TERRAFORM_APPLY_ROLE_ARN` | output `apply_role_arn` |

```bash
gh variable set AWS_TERRAFORM_PLAN_ROLE_ARN --repo PawanITC/ITube --body "$(terraform output -raw plan_role_arn)"
gh variable set AWS_TERRAFORM_APPLY_ROLE_ARN --env dev --repo PawanITC/ITube --body "$(terraform output -raw apply_role_arn)"
```

(Run these from `infra/bootstrap`; add reviewers in the web UI.) **No secrets are needed.** If a resource needs a
password, generate it with `random_password` and store it in AWS Secrets Manager rather than in GitHub.

If bootstrap printed a different bucket name, update `bucket` in [`envs/dev/versions.tf`](envs/dev/versions.tf).

## 3. Use it

1. Add resources to `envs/dev` on a branch and open a pull request. Check the plan comment.
2. Merge. Approve the *Apply* job when it asks. The first run with an empty config only proves the login and
   state work. This configuration now includes a paid MSK cluster; review its plan before approving any apply.

Running `plan` locally against the shared state: log in to AWS, then `cd infra/envs/dev && terraform init && terraform plan`.
Leave `apply` to the workflow.

## MSK integration on the existing EKS cluster

`envs/dev/msk.tf` uses this workflow's provider and `dev/terraform.tfstate` state.
The former `deploy/terraform/msk.tf` has been removed, so there is one MSK definition.
It creates no EKS cluster, VPC, subnet, NAT gateway, node group or shared IAM role.
Read-only discovery expects the existing `eks-lab-dev` cluster in London, its two
private subnets tagged `kubernetes.io/role/internal-elb=1` in different AZs, and its
node SG tagged `Name=eks-lab-dev-node` and `kubernetes.io/cluster/eks-lab-dev=owned`.
Confirm those tags with the infrastructure owner before applying; there is no public-subnet fallback.
The existing EKS Pod Identity agent and namespace `itube` must already exist.

The dedicated roles retain their `itube-*` names, which fit the existing bootstrap
apply policy. Existing roles/policies and infrastructure remain under their current owners.
If anyone previously applied MSK from another state, stop and coordinate a state
handover before planning a second owner. Do not apply both roots for MSK.

The two-broker development cluster uses replication factor 2 and minimum ISR 2,
including internal offsets/transaction topics. One unavailable broker therefore
pauses writes rather than acknowledging a single remaining copy. This is a
cost-conscious development configuration, not three-AZ production availability.
Existing topics need an operator-led replica reassignment; changing defaults
does not replicate old data or transfer messages/consumer offsets between clusters.

After an approved infrastructure apply, obtain the non-secret bootstrap output:

```bash
terraform -chdir=infra/envs/dev output -raw msk_bootstrap_brokers_sasl_iam
```

Set that complete value as the GitHub environment `dev` variable
`MSK_BOOTSTRAP_SERVERS`. The existing EKS workflow then includes the private,
read-only Kafka UI in its normal Kustomize deployment and waits for its rollout.
It needs no extra MSK or state-read permissions on the existing deployment role.
Unset bootstrap servers leave the current deployment flow unchanged.

**Keep `ENGAGEMENT_MSK_ENABLED` unset/false for now.** Engagement and Catalog keep
using the existing Kafka broker. Only after the migration owner coordinates
Catalog's `video.deleted` stream, dependent services, old-broker draining and
replay positions may they set BOTH `CATALOG_MSK_MIGRATION_READY=true` and
`ENGAGEMENT_MSK_ENABLED=true` in environment `dev`. The workflow rejects an
Engagement switch without confirmation/bootstrap servers. It replaces Engagement
inside the same Kustomize build, retaining the chosen broker across releases.
Do not use a flag change as an unplanned rollback: drain and coordinate event
coverage in both directions. See `deploy/engagement-msk/README.md` for the runbook.

PR checks validate both MSK manifests without AWS access; images/deployment jobs
run only on master pushes or manual releases. The Terraform workflow already
plans/validates this root. None of these configuration changes provisions AWS by itself.

## Adding an environment

Copy `envs/dev` to `envs/prod`, change `key` in its backend block (`prod/terraform.tfstate`) and the `environment`
default, then add a matching job or workflow, a second apply role (`apply_environment = "prod"`) and its
environment in GitHub.
