# Deploying ITube to EKS (dev)

The workflow [`.github/workflows/deploy-eks.yml`](../.github/workflows/deploy-eks.yml) runs every test (unit + BDD),
builds one image per service plus the gateway and frontend, pushes them to Amazon ECR and deploys the whole app to
the EKS cluster `eks-lab-dev` (account `160198385987`, region `eu-west-2`), namespace `itube`.

| Directory | What | How it's applied |
|---|---|---|
| [`terraform/`](terraform) | AWS: VPC, EKS (+ metrics-server), ECR repositories, media bucket, IAM roles | `terraform apply` (see its README) |
| [`k8s/`](k8s) | Kubernetes: data stores, the services, gateway, frontend | the deploy workflow (`kubectl apply -k`) |
| [`k8s-cloudflare/`](k8s-cloudflare) | `k8s/` plus the Cloudflare Tunnel connector | the workflow, when `CLOUDFLARE_TUNNEL_TOKEN` is set |
| [`cloudflare/`](cloudflare) | Cloudflare: DNS, tunnel, CDN cache rules, edge rate limit, media CDN | `terraform apply` (see its README) |
| [`k8s-local/`](k8s-local) | `k8s/` on Docker Desktop's Kubernetes with SeaweedFS instead of S3 | by hand |

**Until deploy/terraform is applied, the deploy job fails** at the AWS login or `kubectl` step.

## Before the first deploy of the microservices version

1. Apply [deploy/terraform](terraform) again. It adds the ECR repositories for the new images, the metrics-server
   add-on (the HorizontalPodAutoscalers need it), S3 permissions for tagging and resumable uploads, and CORS on the
   media bucket.
2. Set the new GitHub secrets `ITUBE_INTERNAL_API_TOKEN` and `ITUBE_JWT_PRIVATE_KEY` (below). The deploy fails
   without `ITUBE_JWT_PRIVATE_KEY`.
3. Optional: Cloudflare ([cloudflare/README.md](cloudflare/README.md)).

The workflow deletes the old single `backend` Deployment and Service. The data (Postgres, ScyllaDB, Kafka volumes)
is kept and migrated on start.

## What gets deployed

All manifests are plain YAML in [`k8s/`](k8s), applied with `kubectl apply -k`. They contain only namespaced
objects (no Namespace, StorageClass, RBAC, Ingress or LoadBalancer).

| Workload | Kind | Replicas (HPA) | Image | Notes |
|---|---|---|---|---|
| `identity` | Deployment | 2-4 | `eks-lab/itube-identity` | accounts, sign-in |
| `catalog` | Deployment | 2-8 | `eks-lab/itube-catalog` | ServiceAccount `backend` (S3 via Pod Identity); shards `postgres` + `postgres-shard1` |
| `engagement` | Deployment | 2-6 | `eks-lab/itube-engagement` | ScyllaDB |
| `payments` | Deployment | 1-3 | `eks-lab/itube-payments` | Stripe secrets |
| `transcode-worker` | Deployment | 1-4 | `eks-lab/itube-transcode-worker` | FFmpeg; ServiceAccount `backend`; 5 GiB `/tmp` |
| `gateway` | Deployment | 2-6 | `eks-lab/itube-gateway` | nginx routing `/api/**` to the services |
| `frontend` | Deployment | 1 | `eks-lab/itube-frontend` | nginx; `/api/` -> `gateway` |
| `cloudflared` | Deployment (k8s-cloudflare only) | 2 | `cloudflare/cloudflared` | Cloudflare Tunnel |
| `postgres` | StatefulSet | 1 | `postgres:17-alpine` | identity, payments, catalog shard 0; 20Gi `gp3` |
| `postgres-shard1` | StatefulSet | 1 | `postgres:17-alpine` | catalog shard 1; 10Gi `gp3` |
| `scylla` | StatefulSet | 1 (developer mode) | `scylladb/scylla:6.2` | 20Gi `gp3` |
| `kafka` | StatefulSet | 1 (KRaft) | `apache/kafka:4.0.0` | 5Gi `gp3` |
| `redis` | Deployment | 1 | `redis:7-alpine` | payment rate limits (lost on restart) |

Every service:

- shares settings through the ConfigMap [`itube-config`](k8s/configmap.yaml) (Redis, Kafka, the other services'
  URLs, `FRONTEND_BASE_URL`, `CDN_MEDIA_BASE_URL`) and secrets through `itube-secrets`;
- listens on 8080 in the container, has startup/readiness/liveness probes on `/actuator/health/*`, a `preStop`
  pause and graceful shutdown, so rolling deploys (`maxUnavailable: 0`) drop no requests;
- is spread over nodes and zones, has a HorizontalPodAutoscaler (CPU 70%) and, with 2+ replicas, a
  PodDisruptionBudget;
- runs as non-root UID 10001 with a read-only root filesystem, no privilege escalation, all capabilities dropped and
  `seccompProfile: RuntimeDefault` (Pod Security `restricted`). ScyllaDB's image starts as root, so the namespace
  enforces `baseline` and only warns/audits on `restricted` ([`terraform/kubernetes.tf`](terraform/kubernetes.tf)).

Media files are in the S3 bucket from [`terraform/storage.tf`](terraform/storage.tf), not in the cluster. catalog
and transcode-worker run as ServiceAccount `backend`, which EKS Pod Identity maps to an IAM role for that bucket, so
there are no S3 keys anywhere. SeaweedFS (the local S3 stand-in) only exists in the [`k8s-local`](k8s-local) overlay.

### Resource budget

Two t3.large nodes (~7 GiB allocatable each), shared with the system add-ons. At their minimum replica counts:

| | Memory requested |
|---|---|
| Data stores: ScyllaDB 1.5 GiB, Kafka 768 MiB, Postgres 512 MiB + shard 256 MiB, Redis 64 MiB | ~3.1 GiB |
| Services: 2x identity 384 MiB, 2x catalog 512 MiB, 2x engagement 384 MiB, payments 384 MiB, transcode 768 MiB | ~3.7 GiB |
| Edge: 2x gateway, frontend, 2x cloudflared | ~0.2 GiB |
| **Total** | **~6.9 GiB** |

That leaves about 5 GiB for the autoscalers to add pods under load. For more, raise `node_count` in
deploy/terraform or add Karpenter / the cluster autoscaler.

### OpenTelemetry

The services export metrics, traces and logs over OTLP to `http://localhost:4318` by default, which does not exist
in the cluster. Every Deployment turns the three exporters off with Spring Boot 4 properties set as environment
variables:

| Property | Env variable |
|---|---|
| `management.otlp.metrics.export.enabled=false` | `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED` |
| `management.tracing.export.otlp.enabled=false` | `MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED` |
| `management.logging.export.otlp.enabled=false` | `MANAGEMENT_LOGGING_EXPORT_OTLP_ENABLED` |

Tracing itself stays enabled, so trace/span ids still appear in the console logs (and are passed between services
on every internal call). When a collector is added, remove these variables and point the `management.*.endpoint` /
`management.otlp.metrics.export.url` properties at it.

## One-time GitHub setup (repository PawanITC/ITube)

1. **Environment `dev`** (Settings -> Environments -> New environment -> `dev`). It is mandatory: the deploy role
   only trusts the OIDC subject `repo:PawanITC@239576472/ITube@1418608547:environment:dev`.

   ```bash
   gh api -X PUT repos/PawanITC/ITube/environments/dev
   ```

2. **Variable `AWS_DEPLOY_ROLE_ARN`** (environment or repository level):

   ```bash
   gh variable set AWS_DEPLOY_ROLE_ARN --env dev --repo PawanITC/ITube \
     --body arn:aws:iam::160198385987:role/eks-lab-dev-app-itube
   ```

3. **Secrets** (environment `dev` recommended; repository-level secrets also work - drop `--env dev`):

   | Secret | Required | Value |
   |---|---|---|
   | `ITUBE_DB_PASSWORD` | yes | random password for the `itube` Postgres user (both Postgres servers) |
   | `ITUBE_PLAYBACK_SIGNING_KEY` | yes | 32+ random bytes, base64 |
   | `ITUBE_JWT_PRIVATE_KEY` | yes | RSA key (PKCS#8 PEM) that signs access tokens ([docs/AUTH.md](../docs/AUTH.md)) |
   | `ITUBE_JWT_PREVIOUS_PUBLIC_KEY` | no | only while rotating the signing key ([docs/AUTH.md](../docs/AUTH.md#rotating-the-signing-key)) |
   | `ITUBE_INTERNAL_API_TOKEN` | strongly recommended | random string; services require it on `/internal/**` calls to each other |
   | `ITUBE_STRIPE_SECRET_KEY` | no | Stripe **test** key (`rk_test_...`/`sk_test_...`); without it payments return 503 |
   | `ITUBE_STRIPE_WEBHOOK_SECRET` | no | `whsec_...` (webhooks need a public URL: Cloudflare) |
   | `CLOUDFLARE_TUNNEL_TOKEN` | no | `terraform output -raw tunnel_token` in deploy/cloudflare; publishes the app |

   ```bash
   openssl rand -base64 32 | tr -d '\n' | gh secret set ITUBE_DB_PASSWORD --env dev --repo PawanITC/ITube
   openssl rand -base64 32 | tr -d '\n' | gh secret set ITUBE_PLAYBACK_SIGNING_KEY --env dev --repo PawanITC/ITube
   openssl rand -base64 32 | tr -d '\n' | gh secret set ITUBE_INTERNAL_API_TOKEN --env dev --repo PawanITC/ITube
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 | gh secret set ITUBE_JWT_PRIVATE_KEY --env dev --repo PawanITC/ITube
   # optional
   gh secret set ITUBE_STRIPE_SECRET_KEY --env dev --repo PawanITC/ITube      # prompts for the value
   gh secret set ITUBE_STRIPE_WEBHOOK_SECRET --env dev --repo PawanITC/ITube
   ```

   The workflow stops with a clear `::error::` if the variable or a required secret is missing.

   **Database password:** Postgres only reads it when the volume is first initialised. Changing
   `ITUBE_DB_PASSWORD` later updates the Secret but not the databases, so the services can no longer log in. To
   rotate it, change the password in both Postgres servers first (`ALTER USER itube PASSWORD '...'`), then the
   GitHub secret.

## Deploying

- Automatically on every push to `master` that touches `backend/`, `frontend/`, `gateway/`, `deploy/k8s*` or the
  workflow.
- Manually: Actions -> *Deploy to EKS (dev)* -> *Run workflow*, or `gh workflow run deploy-eks.yml --repo PawanITC/ITube`.

Steps:

1. **Tests**: `./mvnw test` (unit tests and Cucumber scenarios of every service; no external services needed) and
   `vitest run`. The Cucumber HTML reports are uploaded as an artifact.
2. **Images** (in parallel): identity, catalog, engagement, payments, transcode-worker (all from
   [`backend/Dockerfile`](../backend/Dockerfile) with `--build-arg SERVICE=...`), gateway and frontend, tagged with the
   commit SHA and pushed to ECR (skipped if the tag already exists, e.g. on a re-run).
3. **Deploy**: Secrets `itube-secrets` (and `cloudflare-tunnel`), `kustomize edit set image`, `kubectl apply -k`
   ([`k8s`](k8s), or [`k8s-cloudflare`](k8s-cloudflare) when the tunnel token is set), delete the old `backend`
   Deployment, `kubectl rollout status` for every workload.

If the Secret changed, the services are restarted so they pick up the new values. On failure the job prints pods,
events, `describe` output and recent logs. Deploys never run in parallel (`concurrency: deploy-eks-dev`).

The first deploy takes longest: EBS volumes are created, images pulled and the services create their schemas
(the startup probes allow ~5 minutes).

## Opening the app

**With Cloudflare** ([cloudflare/README.md](cloudflare/README.md)): `https://itube.<your domain>`.

**Without it** (no public endpoint), with AWS credentials that can access the cluster:

```bash
aws eks update-kubeconfig --region eu-west-2 --name eks-lab-dev
kubectl port-forward -n itube svc/frontend 3000:80
# App: http://localhost:3000

# Swagger UI of one service (second terminal):
kubectl port-forward -n itube svc/catalog 8082:8080
# http://localhost:8082/swagger-ui.html
```

`FRONTEND_BASE_URL` (ConfigMap `itube-config`) is `https://itube.fun` for Stripe redirect URLs. The local Kubernetes
overlay overrides it to `http://localhost:3000`. When testing Stripe through an EKS port-forward, override it to
the local URL for that deployment.

## Logs and troubleshooting

```bash
kubectl -n itube get pods,svc,pvc,hpa
kubectl -n itube logs deploy/catalog -f                     # any service: identity, catalog, engagement, payments...
kubectl -n itube logs deploy/catalog -c wait-for-dependencies   # stuck in Init?
kubectl -n itube logs deploy/gateway
kubectl -n itube logs statefulset/postgres
kubectl -n itube logs statefulset/postgres-shard1
kubectl -n itube describe hpa catalog                       # why it did (not) scale
kubectl -n itube get events --sort-by=.lastTimestamp
```

Render the manifests locally: `kubectl kustomize deploy/k8s` (or `deploy/k8s-cloudflare`).

## Data persistence

- Postgres (identity, payments, catalog shard 0), postgres-shard1 (catalog shard 1), ScyllaDB and Kafka live on
  EBS-backed PVCs. They survive redeploys, pod restarts and node replacement.
- Videos, renditions and thumbnails are in S3, not in the cluster.
- Redis has no persistence: a restart only logs everyone out.
- **Destroying the EKS environment (or deleting the namespace `itube`) deletes the PVCs and therefore all data.**
  Back up first if needed:
  ```bash
  kubectl -n itube exec postgres-0 -- pg_dump -U itube itube > itube.sql
  kubectl -n itube exec postgres-shard1-0 -- pg_dump -U itube itube > itube-shard1.sql
  ```

## Assumptions about the infra side

- Namespace `itube` exists; the deploy role has `AmazonEKSEditPolicy` scoped to it (enough for everything here,
  including HPAs and PodDisruptionBudgets).
- StorageClass `gp3` exists (EBS CSI driver).
- The node role can pull from ECR (`AmazonEC2ContainerRegistryReadOnly`).
- The metrics-server add-on is installed (deploy/terraform/eks.tf), or the HPAs show `<unknown>` and never scale.
