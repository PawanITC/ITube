provider "docker" {}

resource "docker_image" "nginx" {
  name         = "nginx:latest"
  keep_locally = false
}

resource "docker_container" "nginx" {
  image = docker_image.nginx.image_id
  name  = "tutorial"
  ports {
    internal = 80
    external = 8000
  }
}

resource "docker_image" "postgres" {
  name         = "postgres:17-alpine"
  keep_locally = true
}

# IN MAKEFILE I'M CREATING A VOLUME ---- TO INSTALL MAKE FILE USE THE CMD IN ADMIN MODE 'choco install makefile'
# resource "docker_volume" "postgres_data" {
#   name = "itube-postgres-data"
# }

resource "docker_network" "database" {
  name = "itube-database"
}

resource "docker_container" "postgres" {
  image = docker_image.postgres.image_id
  name  = "itube-postgres"

  env = [
    "POSTGRES_DB=itube",
    "POSTGRES_USER=itube",
    "POSTGRES_PASSWORD=itube-dev-password",
  ]

  ports {
    internal = 5432
    external = 5434
  }

  volumes {
    volume_name    = "postgres_data"
    container_path = "/var/lib/postgresql/data"
  }

  networks_advanced {
    name = docker_network.database.name
  }
}

resource "docker_image" "pgadmin" {
  name         = "dpage/pgadmin4:latest"
  keep_locally = true
}

resource "docker_container" "pgadmin" {
  image = docker_image.pgadmin.image_id
  name  = "itube-pgadmin"

  env = [
    "PGADMIN_DEFAULT_EMAIL=admin@example.com",
    "PGADMIN_DEFAULT_PASSWORD=password",
  ]

  ports {
    internal = 80
    external = 5050
  }

  networks_advanced {
    name = docker_network.database.name
  }
}

output "postgres_connection" {
  value     = "postgresql://itube:itube-dev-password@localhost:5432/itube"
  sensitive = true
}

resource "docker_image" "grafana_lgtm" {
  name         = "grafana/otel-lgtm:latest"
  keep_locally = true
}

resource "docker_container" "grafana_lgtm" {
  name  = "grafana-lgtm"
  image = docker_image.grafana_lgtm.image_id

  ports {
    internal = 3000
    external = 3080
  }

  ports {
    internal = 4317
    external = 4317
  }

  ports {
    internal = 4318
    external = 4318
  }
}

resource "docker_image" "redis" {
  name         = "redis:7-alpine"
  keep_locally = true
}

resource "docker_container" "redis" {
  name  = "itube-redis"
  image = docker_image.redis.image_id

  ports {
    internal = 6379
    external = 6379
  }
}

resource "docker_image" "kafka" {
  name         = "apache/kafka:4.0.0"
  keep_locally = true
}

resource "docker_container" "kafka" {
  image = docker_image.kafka.image_id
  name  = "itube-kafka"

  env = [
    "KAFKA_NODE_ID=1",
    "KAFKA_PROCESS_ROLES=broker,controller",
    "KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093",
    "KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092",
    "KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER",
    "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT",
    "KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093",
    "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1",
    "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1",
    "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1",
    "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0",
    "KAFKA_NUM_PARTITIONS=3",
  ]

  ports {
    internal = 9092
    external = 9092
  }
}
