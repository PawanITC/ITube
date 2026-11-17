# Cluster-scoped objects the manifests in deploy/k8s expect but do not create.

resource "kubernetes_storage_class_v1" "gp3" {
  metadata {
    name = "gp3"
  }

  storage_provisioner    = "ebs.csi.aws.com"
  volume_binding_mode    = "WaitForFirstConsumer" # create the volume in the AZ the pod lands in
  allow_volume_expansion = true
  reclaim_policy         = "Delete"

  parameters = {
    type      = "gp3"
    encrypted = "true"
  }

  depends_on = [module.eks]
}

resource "kubernetes_namespace_v1" "app" {
  metadata {
    name = var.namespace
    labels = {
      # baseline, not restricted: the ScyllaDB image starts as root (its supervisord drops to the scylla user
      # itself) and keeps the default capabilities, which restricted rejects. Everything else (backend,
      # frontend, Postgres, Redis, Kafka) still meets restricted; warn/audit keep that visible.
      "pod-security.kubernetes.io/enforce" = "baseline"
      "pod-security.kubernetes.io/warn"    = "restricted"
      "pod-security.kubernetes.io/audit"   = "restricted"
    }
  }

  depends_on = [module.eks]
}
