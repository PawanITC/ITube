# Private, read-only Kafka UI for MSK

Reuses the local setup's **ghcr.io/kafbat/kafka-ui:v1.5.1** image on the existing EKS cluster. These files are
rendered into the existing `deploy/k8s` CI/CD deployment when the `dev` environment variable
`MSK_BOOTSTRAP_SERVERS` is set. Local `docker-compose.yml` and its Kafka UI stay unchanged.

- One small pod, a ConfigMap, service account and **ClusterIP** Service in the existing `itube` namespace.
- No public endpoint, load balancer, extra worker nodes, database, disk volume or AWS service is requested.
  The pod uses existing node capacity; CPU/memory availability still needs checking before a future deployment.
- Private MSK IAM bootstrap servers on **9098**, **SASL_SSL**, the bundled **AWS_MSK_IAM** plugin and normal TLS
  certificate/hostname verification. No static AWS keys, role ARN annotations or downloaded plugins are used.
- Service account **itube/kafka-ui** matches the dedicated role's EKS Pod Identity association already defined
  in `../../infra/envs/dev/msk.tf`. EKS supplies credentials through its existing Pod Identity agent. Node-role fallback
  is disabled. `automountServiceAccountToken: false` does not disable Pod Identity's separate projected AWS token.
- All topics in the ITube MSK cluster are visible: there is no topic filter. UI **readOnly** mode and the
  existing IAM policy prevent producing or topic/configuration changes. Only **kafka-ui-viewer** is used for
  browsing, matching the policy's `kafka-ui-*` prefix; application group offsets cannot be changed.
- Dynamic configuration edits and unsupported MSK log-directory collection are disabled.

## Render for review without accessing AWS

MSK has not been provisioned by this change, so its actual bootstrap hostnames do not exist yet. After the
infrastructure is approved and available, obtain the existing Terraform output (a read-only command):

```powershell
$brokers = terraform -chdir=infra/envs/dev output -raw msk_bootstrap_brokers_sasl_iam
```

From the repo root, render a reviewable manifest using that value:

```powershell
.\deploy\kafka-ui-msk\render.ps1 -BootstrapServers $brokers -OutputPath "$env:TEMP\itube-msk-ui.yaml"
```

The renderer only reads these files and writes YAML. It does not invoke Terraform/kubectl or deploy anything.
It rejects plaintext ports, URLs, non-London hosts and missing bootstrap values. Do not apply the template
directly or use invented hostnames for a real deployment. The namespace/service-account names must match
the Terraform Pod Identity association; these manifests follow the current `itube/kafka-ui` defaults.

## Offline validation

Use the following clearly fictional endpoints only to validate the rendered schema locally:

```powershell
.\deploy\kafka-ui-msk\render.ps1 -BootstrapServers "b-1.example.abc.c1.kafka.eu-west-2.amazonaws.com:9098,b-2.example.abc.c1.kafka.eu-west-2.amazonaws.com:9098" -OutputPath "$env:TEMP\itube-msk-ui-check.yaml"
docker run --rm -v "${env:TEMP}\itube-msk-ui-check.yaml:/work/manifest.yaml:ro" ghcr.io/yannh/kubeconform:v0.8.0 -strict -summary -kubernetes-version 1.36.0 /work/manifest.yaml
```

This checks Kubernetes schemas without calling the cluster. Live IAM authentication, MSK reachability and
Pod Identity injection require a later approved deployment; offline validation does not prove them.

## Access after a separately reviewed deployment

The existing EKS workflow includes these resources only when `MSK_BOOTSTRAP_SERVERS` is configured.
After the infrastructure and application releases are separately approved and complete, use existing Kubernetes access:

```powershell
kubectl -n itube port-forward service/kafka-ui-msk 8091:8080
```

Open **http://localhost:8091**. The local Docker UI remains on **http://localhost:8090**. The MSK UI deliberately
has no public ingress or separate browser login; port-forward access is controlled by Kubernetes RBAC.
ClusterIP also remains reachable from permitted workloads inside the cluster's existing network.

Sources: [Kafbat MSK authentication](https://ui.docs.kafbat.io/quick-start/prerequisites/permissions/msk-+serverless-setup),
[EKS Pod Identity credential support](https://docs.aws.amazon.com/eks/latest/userguide/pod-id-minimum-sdk.html).
