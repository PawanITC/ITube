terraform {
  required_providers {
    docker = {
      source = "kreuzwerker/docker"
      version = "~> 4.2.0"
    }
    kafka-connect = {
      source = "Mongey/kafka-connect"
    }
  }
}