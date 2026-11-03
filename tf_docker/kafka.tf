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
    # Lets the connector raise its own consumer fetch size for large renditions
    "CONNECT_CONNECTOR_CLIENT_CONFIG_OVERRIDE_POLICY=All",
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

resource "kafka-connect_connector" "video_rendition_sink" {
  name = "video_rendition_sink"
  config = {
    "name"                                        = "video_rendition_sink"
    "connector.class"                             = "io.debezium.connector.jdbc.JdbcSinkConnector"
    "topics"                                      = "video.rendition"
    "connection.url"                              = "jdbc:postgresql://itube-postgres:5432/itube"
    "connection.username"                         = "itube"
    "insert.mode"                                 = "upsert"
    "primary.key.mode"                            = "record_value"
    "primary.key.fields"                          = "video_id,quality"
    "collection.name.format"                      = "video_rendition"
    "schema.evolution"                            = "none"
    "key.converter"                               = "org.apache.kafka.connect.storage.StringConverter"
    "value.converter"                             = "org.apache.kafka.connect.json.JsonConverter"
    "value.converter.schemas.enable"              = "true"
    "consumer.override.max.partition.fetch.bytes" = "104857600"
  }
  config_sensitive = {
    "connection.password" = "itube-dev-password"
  }
  depends_on = [docker_container.kafka_connect]
}
