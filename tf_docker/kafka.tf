resource "docker_image" "kafka_connect" {
  name         = "quay.io/debezium/connect:3.1"
  keep_locally = true
}

resource "docker_container" "kafka_connect" {
  image = docker_image.kafka_connect.image_id
  name  = "itube-kafka-connect"

  env = [
    "BOOTSTRAP_SERVERS=itube-kafka:29092",
    "GROUP_ID=itube-connect",
    "CONFIG_STORAGE_TOPIC=connect-configs",
    "OFFSET_STORAGE_TOPIC=connect-offsets",
    "STATUS_STORAGE_TOPIC=connect-status",
    # Single broker, so the Connect internal topics can only have one replica
    "CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR=1",
    "CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR=1",
    "CONNECT_STATUS_STORAGE_REPLICATION_FACTOR=1",
  ]

  ports {
    internal = 8083
    external = 8083
  }

  networks_advanced {
    name = docker_network.database.name
  }

  # Wait until the REST API is up so the connector below can be registered on the first apply
  healthcheck {
    test         = ["CMD-SHELL", "curl -sf http://localhost:8083/connectors || exit 1"]
    interval     = "5s"
    timeout      = "5s"
    retries      = 30
    start_period = "10s"
  }
  wait         = true
  wait_timeout = 180

  depends_on = [docker_container.kafka, docker_container.postgres]
}

provider "kafka-connect" {
  url = "http://localhost:8083"
}

# The video_rendition_sink connector was removed: renditions are now written to object storage by the
# transcode worker, which records their keys in catalog.video_rendition itself. Kafka Connect stays for
# future change-data-capture (e.g. Debezium feeding a search index).
