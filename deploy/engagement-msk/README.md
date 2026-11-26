# Engagement on AWS MSK (opt-in; no deployment performed)

This overlay reuses `../k8s/engagement.yaml` and the EKS Pod Identity association
for **`itube/engagement`** in `../../infra/envs/dev/msk.tf`. The existing EKS workflow
can include it, but defaults to the original broker until the migration flags below
are explicitly enabled. The shared ConfigMap, local Docker Kafka and other services
stay unchanged. The overlay itself adds no AWS resources.

The `msk` Spring profile secures the existing Kafka producer, command consumer,
Catalog video-deletion consumer and KafkaAdmin with **SASL_SSL / AWS_MSK_IAM**.
TLS uses the JVM's normal trusted public CAs and verifies broker hostnames.
The AWS SDK default credential chain obtains temporary credentials from the
EKS Pod Identity agent. Do not supply access keys, profiles or an `awsRoleArn`;
do not add an IRSA annotation. The manifest disables EC2 metadata fallback.
No credential is stored in the ConfigMap.

The IAM authentication dependency is `aws-msk-iam-auth:2.4.0`. Engagement alone
imports its matching AWS SDK BOM (`2.51.2`); the shared parent BOM and other
services are unchanged. See [AWS's plugin documentation](https://github.com/aws/aws-msk-iam-auth)
and [Pod Identity SDK requirements](https://docs.aws.amazon.com/eks/latest/userguide/pod-id-minimum-sdk.html).

## Mandatory migration dependencies — resolve BEFORE changing brokers

**Do not switch Engagement alone.** Both of its listeners use the same Kafka
configuration. Catalog currently sends `video.deleted` to the existing Kafka
broker. After an isolated Engagement switch, video deletion would stop reaching
Engagement's cleanup listener: likes and comments would no longer be removed.
Watch history is deliberately retained by the existing cleanup logic.

| Existing stream | Producer → consumer | Required migration coordination |
| --- | --- | --- |
| `engagement.commands.v1` | Engagement API → Engagement, group `itube-engagement-commands-v1` | Drain accepted commands and consumer lag on the old broker before switching all Engagement replicas. |
| `video.deleted` | Catalog → Engagement, group `itube-engagement-cleanup`; also Catalog, group `itube-media-cleanup` | Catalog and Engagement must share a broker or have an explicitly approved, tested relay for this stream. Preserve keys, payloads and deletion coverage. |
| `video.transcode`, `video.transcoded` | Catalog ↔ Transcode Worker | Moving Catalog's global broker also requires coordinating the worker. |
| `account.created` | Identity fallback publisher → Catalog | Coordinate the producer or a relay when migrating Catalog. |
| `catalog.cache-invalidation` | Catalog pods ↔ Catalog pods | Keep all Catalog replicas on the same event stream. |

Catalog's deletion publisher runs after the database transaction commits and
logs publish failures; it has no durable deletion outbox. Pausing only consumers
does not make the producer safe during broker unavailability. Agree on a write
pause or reliable event relay/reconciliation before cutover. Nothing in this
change modifies Catalog, its IAM role or any other service.

Before the release:

1. Provisioning must already be completed by the infrastructure owner: MSK,
   the dedicated role/policy and Pod Identity association from
   `../../infra/envs/dev/msk.tf`, and the existing EKS Pod Identity agent. Verify private
   DNS and node/pod connectivity to MSK on TCP 9098. Namespace must be `itube`
   to match this overlay and the prepared association. Live access is not
   verified by the offline checks below.
2. Have the topic owner/operator create or migrate `video.deleted` on MSK.
   Engagement's role can read it but deliberately **cannot create it**. Its
   writable/admin topics are `engagement.commands.v1`,
   `engagement.commands.v1-dlt` and `video.deleted-dlt`. Check the existing IAM
   allowlist if any topic/group environment override is used.
3. Keep the current topic names, six partitions and group names. This change
   does not alter retention, acknowledgments or serializers. MSK's broker defaults
   and the MSK-only Engagement profile use replication factor **2**; the overlay
   overrides the shared single-broker environment value too. Existing local/in-cluster
   defaults stay **1**. Obtain topic-owner approval and pre-create `video.deleted`
   with two replicas. Existing topic replication does not automatically increase
   when the application restarts; an operator must reassign existing replicas. Do not reduce an
   existing partition count or change key partitioning during migration.
4. Freeze Engagement writes, drain old command and deletion consumers, and
   account for pending operations/DLTs. Preserve ScyllaDB and its operation
   receipts. New clusters have independent offsets even with identical group
   names; do not copy raw offsets or assume Kafka transfers data automatically.
   Establish replay/offset positions with the migration owner. Idempotency
   receipts do not replace migration ordering or prevent missing events.
5. Build and publish a new Engagement image with the dependency/profile in
   this change. Render with its immutable release tag/digest, not an older image.
6. Coordinate the Catalog event migration/relay and confirm both command
   processing and video deletion cleanup before reopening writes. `Recreate`
   prevents overlapping old/MSK Engagement pods during this cutover and entails
   a brief outage; it does not migrate messages by itself. Rollback also needs
   a drain and event-coverage plan.

**Release controls:** the existing EKS workflow now includes Kafka UI when the `dev`
environment variable `MSK_BOOTSTRAP_SERVERS` contains the Terraform IAM endpoints.
Engagement still uses its existing broker by default. After the migration owner
confirms all dependencies above, set BOTH `CATALOG_MSK_MIGRATION_READY=true` and
`ENGAGEMENT_MSK_ENABLED=true`. The workflow rejects cutover without confirmation
and replaces the Engagement base inside the same build; subsequent releases retain
the selected broker. These flags are operator confirmation, not automatic proof
that Catalog is migrated. Do not enable them before coordinating the migration.
MSK minimum ISR 2 on two brokers deliberately stops writes if one broker is down.

## Render and validate locally

Run from the repository root in PowerShell. Set `$mskBootstrap` to the actual
private SASL/IAM bootstrap output **after infrastructure exists** (output name
`msk_bootstrap_brokers_sasl_iam` in `infra/envs/dev`). Do not use the local
broker address. `$engagementImage` must identify the rebuilt Engagement image.
Both are caller-supplied; no broker or account address is embedded here.

```powershell
$mskBootstrap = '<private London IAM bootstrap servers, comma separated>'
$engagementImage = '<your Engagement registry/repository:release-tag>'
$rendered = Join-Path $env:TEMP 'itube-engagement-msk.yaml'
& .\deploy\engagement-msk\render.ps1 -BootstrapServers $mskBootstrap -Image $engagementImage -OutputPath $rendered
docker run --rm -v "${rendered}:/work/manifest.yaml:ro" ghcr.io/yannh/kubeconform:v0.8.0 -strict -summary -kubernetes-version 1.36.0 /work/manifest.yaml
```

The renderer calls only **client-side `kubectl kustomize`**, never `kubectl apply`
or AWS APIs. It copies the current base into a unique temporary directory to
render ServiceAccount, ConfigMap and the existing Engagement Service,
Deployment, HPA and disruption budget. No other service is included.

The Deployment uses service account `engagement`, activates the `msk` profile,
and sets `KAFKA_BOOTSTRAP_SERVERS` explicitly from the new ConfigMap, overriding
the old shared `envFrom` value. Its init container still checks Scylla,
PostgreSQL and Redis but no longer waits for the old `kafka:9092` service.
KafkaAdmin fails application startup on MSK initialization errors. All other
base probes, resources, secrets, networking and data configuration are retained.

Tests from `backend` (no AWS credentials required):

```powershell
.\mvnw.cmd -pl engagement-service -am test
# Optional real local integration tests, with local Kafka :9092 and ScyllaDB :9042 already running:
.\mvnw.cmd -pl engagement-service -am test '-Ditube.test.kafka=true' '-Ditube.test.scylla=true'
```

Configuration tests bind the real local/MSK profiles, check all three Kafka
client configurations, initialize the runtime IAM login module, and verify
the unchanged topic/group defaults. Local integration tests exercise the
existing command pipeline; successful MSK authentication and IAM authorization
still require an explicitly scheduled EKS smoke test after provisioning.
