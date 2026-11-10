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
> configurations managing the same resource overwrite each other. `envs/dev` starts empty for that reason.

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
   state work (`Apply complete! Resources: 0 added`).

Running `plan` locally against the shared state: log in to AWS, then `cd infra/envs/dev && terraform init && terraform plan`.
Leave `apply` to the workflow.

## Adding an environment

Copy `envs/dev` to `envs/prod`, change `key` in its backend block (`prod/terraform.tfstate`) and the `environment`
default, then add a matching job or workflow, a second apply role (`apply_environment = "prod"`) and its
environment in GitHub.
