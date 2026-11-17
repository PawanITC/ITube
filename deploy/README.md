# Deploying ITube to EKS (dev)

The workflow [`.github/workflows/deploy-eks.yml`](../.github/workflows/deploy-eks.yml) builds the backend and
frontend images, pushes them to Amazon ECR and deploys the whole app to the EKS cluster `eks-lab-dev`
(account `160198385987`, region `eu-west-2`), namespace `itube`.

The AWS side (EKS cluster, namespace `itube`, `gp3` StorageClass, ECR repositories, deploy IAM role) is created
with Terraform in [`terraform/`](terraform) (see its README). **Until that is applied, the deploy job fails** at
the AWS login or `kubectl` step.

## What gets deployed

All manifests are plain YAML in [`k8s/`](k8s), applied with `kubectl apply -k deploy/k8s`. They contain only
namespaced objects (no Namespace, StorageClass, RBAC, Ingress or LoadBalancer).

| Workload | Kind | Image | Service | Storage |
|---|---|---|---|---|
| `postgres` | StatefulSet | `postgres:17-alpine` | `postgres:5432` (headless) | PVC `data-postgres-0`, 20Gi `gp3` |
| `redis` | Deployment | `redis:7-alpine` | `redis:6379` | none (sessions are lost on restart) |
| `kafka` | StatefulSet, single-node KRaft | `apache/kafka:4.0.0` | `kafka:9092` (headless) | PVC `data-kafka-0`, 5Gi `gp3` |
| `backend` | Deployment (1 replica, Recreate) | `eks-lab/itube-backend:<git sha>` | `backend:8080` | `/tmp` emptyDir (3Gi) for uploads/FFmpeg |
| `frontend` | Deployment | `eks-lab/itube-frontend:<git sha>` | `frontend:80` -> 8080 | none |

- **backend** ([`backend/Dockerfile`](../backend/Dockerfile)): Spring Boot jar on Temurin 21 JRE with FFmpeg,
  UID 10001, heap = 75% of the container memory limit.
- **frontend** ([`frontend/Dockerfile`](../frontend/Dockerfile)): Vite build served by `nginx-unprivileged` on 8080.
  nginx proxies `/api/` to `http://backend:8080`, exactly like the Vite dev proxy, so the browser sees one origin.
  It allows 501 MB uploads, streams request bodies (`proxy_request_buffering off`), passes `Range` requests through
  for video streaming and falls back to `index.html` for client-side routes.
- The Secret `itube-secrets` is **not** in the manifests; the workflow creates it from GitHub secrets.
- All pods run as non-root with `allowPrivilegeEscalation: false`, all capabilities dropped and
  `seccompProfile: RuntimeDefault` (passes Pod Security `baseline` and `restricted`). Volume permissions for
  Postgres (UID 70) and Kafka (UID 1000) are handled with `fsGroup`, not root init containers.

### Resource budget

Two t3.medium nodes, shared with the system add-ons. The namespace stays well inside the agreed budget
(<= 2.5 vCPU / 4.5 GiB requests).

| Workload | CPU request | CPU limit | Memory request | Memory limit |
|---|---|---|---|---|
| backend | 500m | 1500m | 1536Mi | 2560Mi |
| postgres | 250m | 1 | 512Mi | 1Gi |
| kafka (heap 256-512 MB) | 250m | 1 | 768Mi | 1Gi |
| redis | 50m | 250m | 64Mi | 256Mi |
| frontend | 50m | 250m | 64Mi | 128Mi |
| **Total** | **1100m** | 4 | **2944Mi (~2.9 GiB)** | ~4.9 GiB |

The backend init container (waits for Postgres, Redis and Kafka) requests 10m / 16Mi and only runs before the
backend starts.

### OpenTelemetry

`application.properties` exports metrics, traces and logs over OTLP to `http://localhost:4318`, which does not exist
in the cluster (without it the backend logs a connection error every 10 s). The backend Deployment turns the three
exporters off with Spring Boot 4 properties, set as environment variables:

| Property | Env variable |
|---|---|
| `management.otlp.metrics.export.enabled=false` | `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED` |
| `management.tracing.export.otlp.enabled=false` | `MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED` |
| `management.logging.export.otlp.enabled=false` | `MANAGEMENT_LOGGING_EXPORT_OTLP_ENABLED` |

(Names taken from the Spring Boot 4.1.1 configuration metadata; the Boot 3 names `management.otlp.tracing.export.enabled`
and `management.otlp.logging.export.enabled` are deprecated with level `error` in Boot 4 and no longer work.)
Tracing itself stays enabled, so trace/span ids are still printed in the console logs, and the OpenTelemetry SDK
bean that `InstallOpenTelemetryAppender` needs still exists; it just has no exporter. No application code was
changed. When a collector is added later, remove these three variables and point the
`management.*.endpoint` / `management.otlp.metrics.export.url` properties at it.

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
   | `ITUBE_DB_PASSWORD` | yes | random password for the `itube` Postgres user |
   | `ITUBE_PLAYBACK_SIGNING_KEY` | yes | 32+ random bytes, base64 |
   | `ITUBE_STRIPE_SECRET_KEY` | no | Stripe **test** key (`rk_test_...`/`sk_test_...`); without it payments return 503 |
   | `ITUBE_STRIPE_WEBHOOK_SECRET` | no | `whsec_...` (webhooks need a public URL, so normally unused in this setup) |

   ```bash
   openssl rand -base64 32 | tr -d '\n' | gh secret set ITUBE_DB_PASSWORD --env dev --repo PawanITC/ITube
   openssl rand -base64 32 | tr -d '\n' | gh secret set ITUBE_PLAYBACK_SIGNING_KEY --env dev --repo PawanITC/ITube
   # optional
   gh secret set ITUBE_STRIPE_SECRET_KEY --env dev --repo PawanITC/ITube      # prompts for the value
   gh secret set ITUBE_STRIPE_WEBHOOK_SECRET --env dev --repo PawanITC/ITube

   # repository-level instead of environment-level:
   openssl rand -base64 32 | tr -d '\n' | gh secret set ITUBE_DB_PASSWORD --repo PawanITC/ITube
   ```

   The workflow stops with a clear `::error::` if the variable or a required secret is missing.

   **Database password:** Postgres only reads it when the volume is first initialised. Changing
   `ITUBE_DB_PASSWORD` later updates the Secret but not the database, so the backend can no longer log in. To
   rotate it, change the password in Postgres first (`ALTER USER itube PASSWORD '...'`), then the GitHub secret.

## Deploying

- Automatically on every push to `master` that touches `backend/`, `frontend/`, `deploy/` or the workflow.
- Manually: Actions -> *Deploy to EKS (dev)* -> *Run workflow*, or `gh workflow run deploy-eks.yml --repo PawanITC/ITube`.

Steps: unit tests (backend `./mvnw test` with the in-memory H2 profile, frontend `vitest run`; neither needs
external services) -> OIDC login to AWS -> build and push both images tagged with the commit SHA (skipped if the
tag already exists, e.g. on a re-run) -> `aws eks update-kubeconfig` -> create/update Secret `itube-secrets` ->
`kustomize edit set image` -> `kubectl apply -k deploy/k8s` -> `kubectl rollout status` for every workload.
If the Secret changed, the backend is restarted so it picks up the new values. On failure the job prints pods,
events, `describe` output and recent logs. Deploys never run in parallel (`concurrency: deploy-eks-dev`).

The first deploy takes longest: EBS volumes are created, images pulled and the backend creates its schema
(the startup probe allows ~5 minutes).

## Opening the app

There is no public endpoint (no Ingress/LoadBalancer, to save cost). With AWS credentials that can access the
cluster:

```bash
aws eks update-kubeconfig --region eu-west-2 --name eks-lab-dev
kubectl port-forward -n itube svc/frontend 3000:80
# App: http://localhost:3000

# Swagger UI / API directly (second terminal):
kubectl port-forward -n itube svc/backend 8080:8080
# http://localhost:8080/swagger-ui.html
```

`FRONTEND_BASE_URL` is `http://localhost:3000`, matching the port-forward (used for Stripe redirect URLs).

## Logs and troubleshooting

```bash
kubectl -n itube get pods,svc,pvc
kubectl -n itube logs deploy/backend -f
kubectl -n itube logs deploy/backend -c wait-for-dependencies   # stuck in Init?
kubectl -n itube logs deploy/frontend
kubectl -n itube logs statefulset/postgres
kubectl -n itube logs statefulset/kafka
kubectl -n itube describe pod -l app.kubernetes.io/name=backend
kubectl -n itube get events --sort-by=.lastTimestamp
```

Render the manifests locally: `kubectl kustomize deploy/k8s`.

## Data persistence

- Postgres (all app data, including uploaded videos and renditions) and Kafka live on EBS-backed PVCs
  (`data-postgres-0`, `data-kafka-0`). They survive redeploys, pod restarts and node replacement.
- Redis has no persistence: a restart only logs everyone out.
- **Destroying the EKS environment (or deleting the namespace `itube`) deletes the PVCs and therefore all data.**
  Back up first if needed: `kubectl -n itube exec postgres-0 -- pg_dump -U itube itube > itube.sql`.
- Videos are stored in Postgres and renditions travel through Kafka as base64 (up to 100 MB records); that is
  fine for a dev demo, not for production-scale media. Kafka keeps messages 24 h / 512 MiB per partition so the
  5Gi volume does not fill up.

## Assumptions about the infra side

- Namespace `itube` exists; the deploy role has `AmazonEKSEditPolicy` scoped to it (enough for everything here).
- StorageClass `gp3` exists (EBS CSI driver).
- The node role can pull from ECR (`AmazonEC2ContainerRegistryReadOnly`).
