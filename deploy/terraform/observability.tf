# Observability backend for the services in deploy/k8s: the same grafana/otel-lgtm image docker-compose.yml and
# tf_docker run locally (OpenTelemetry Collector + Prometheus + Loki + Tempo + Pyroscope + Grafana in one pod).
#
# The services export OTLP over HTTP to the Service below (itube-config in deploy/k8s/configmap.yaml):
#   http://lgtm.<observability_namespace>.svc.cluster.local:4318/v1/{metrics,traces,logs}
# Grafana is not exposed outside the cluster; reach it with the grafana_port_forward_command output.
#
# One replica with all data on one gp3 volume: fine for dev, not highly available. For production, swap this for
# managed backends (Amazon Managed Prometheus / Grafana, or Grafana Cloud) and keep the OTLP endpoint the same.

resource "kubernetes_namespace_v1" "observability" {
  metadata {
    name = var.observability_namespace
    labels = {
      # The otel-lgtm image runs as root, which restricted rejects
      "pod-security.kubernetes.io/enforce" = "baseline"
      "pod-security.kubernetes.io/warn"    = "restricted"
      "pod-security.kubernetes.io/audit"   = "restricted"
    }
  }

  depends_on = [module.eks]
}

# Grafana admin login. Anonymous access (the image's default) is switched off below: in the cluster every pod could
# otherwise reach Grafana as an admin. Grafana only reads this on its first start (when it creates its database);
# to change it later use `grafana cli admin reset-admin-password` in the pod.
resource "random_password" "grafana_admin" {
  length  = 24
  special = false
}

resource "kubernetes_secret_v1" "grafana_admin" {
  metadata {
    name      = "grafana-admin"
    namespace = kubernetes_namespace_v1.observability.metadata[0].name
  }

  data = {
    user     = "admin"
    password = random_password.grafana_admin.result
  }
}

# /data holds Grafana's database, Prometheus TSDB, Loki chunks, Tempo blocks and Pyroscope profiles
resource "kubernetes_persistent_volume_claim_v1" "lgtm" {
  metadata {
    name      = "lgtm-data"
    namespace = kubernetes_namespace_v1.observability.metadata[0].name
  }

  spec {
    access_modes       = ["ReadWriteOnce"]
    storage_class_name = kubernetes_storage_class_v1.gp3.metadata[0].name

    resources {
      requests = {
        storage = "${var.observability_volume_size}Gi"
      }
    }
  }

  # gp3 binds on first use (WaitForFirstConsumer), i.e. when the Deployment below starts
  wait_until_bound = false
}

locals {
  lgtm_labels = {
    "app.kubernetes.io/name"    = "lgtm"
    "app.kubernetes.io/part-of" = "itube"
  }
}

resource "kubernetes_deployment_v1" "lgtm" {
  metadata {
    name      = "lgtm"
    namespace = kubernetes_namespace_v1.observability.metadata[0].name
    labels    = local.lgtm_labels
  }

  spec {
    replicas = 1

    # The volume is ReadWriteOnce: stop the old pod before starting the new one
    strategy {
      type = "Recreate"
    }

    selector {
      match_labels = local.lgtm_labels
    }

    template {
      metadata {
        labels = local.lgtm_labels
      }

      spec {
        container {
          name  = "lgtm"
          image = var.lgtm_image

          port {
            name           = "grafana"
            container_port = 3000
          }
          port {
            name           = "otlp-grpc"
            container_port = 4317
          }
          port {
            name           = "otlp-http"
            container_port = 4318
          }

          env {
            name  = "GF_AUTH_ANONYMOUS_ENABLED"
            value = "false"
          }
          env {
            name = "GF_SECURITY_ADMIN_USER"
            value_from {
              secret_key_ref {
                name = kubernetes_secret_v1.grafana_admin.metadata[0].name
                key  = "user"
              }
            }
          }
          env {
            name = "GF_SECURITY_ADMIN_PASSWORD"
            value_from {
              secret_key_ref {
                name = kubernetes_secret_v1.grafana_admin.metadata[0].name
                key  = "password"
              }
            }
          }

          resources {
            requests = {
              cpu    = "250m"
              memory = var.observability_memory_request
            }
            limits = {
              memory = var.observability_memory_limit
            }
          }

          volume_mount {
            name       = "data"
            mount_path = "/data"
          }

          # Six processes start one after another; Grafana is last
          startup_probe {
            http_get {
              path = "/api/health"
              port = "grafana"
            }
            period_seconds    = 5
            failure_threshold = 60
          }

          # The collector's health_check extension: ready means OTLP is accepted
          readiness_probe {
            http_get {
              path = "/"
              port = 13133
            }
            period_seconds = 10
          }

          liveness_probe {
            http_get {
              path = "/api/health"
              port = "grafana"
            }
            period_seconds    = 20
            failure_threshold = 6
          }
        }

        volume {
          name = "data"
          persistent_volume_claim {
            claim_name = kubernetes_persistent_volume_claim_v1.lgtm.metadata[0].name
          }
        }
      }
    }
  }

  # The first start downloads Grafana plugins; don't fail the apply while it does
  wait_for_rollout = false
}

resource "kubernetes_service_v1" "lgtm" {
  metadata {
    name      = "lgtm"
    namespace = kubernetes_namespace_v1.observability.metadata[0].name
    labels    = local.lgtm_labels
  }

  spec {
    selector = local.lgtm_labels

    port {
      name        = "grafana"
      port        = 3000
      target_port = "grafana"
    }
    port {
      name        = "otlp-grpc"
      port        = 4317
      target_port = "otlp-grpc"
    }
    port {
      name        = "otlp-http"
      port        = 4318
      target_port = "otlp-http"
    }
  }
}
