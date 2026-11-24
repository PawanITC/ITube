# MSK belongs to the CI-managed root and state. Existing EKS/VPC resources stay owned by the team infra stack.
# Read-only lookups below never import or manage that stack's EKS, subnets, node groups or IAM roles.
# New client roles reuse the existing EKS Pod Identity agent; no credentials are stored in application config.
# Development estimate: about USD 80.12/month at 730 hours (2 brokers + 40 GiB storage), before tax/transfer.

variable "cluster_name" {
  description = "Existing team-owned EKS cluster; must match EKS_CLUSTER in the deployment workflow."
  type        = string
  default     = "eks-lab-dev"
}

variable "namespace" {
  description = "Existing application namespace; must match the Kubernetes manifests and workflow."
  type        = string
  default     = "itube"
}

data "aws_eks_cluster" "existing" {
  name = var.cluster_name
}

data "aws_vpc" "existing" {
  id = data.aws_eks_cluster.existing.vpc_config[0].vpc_id
}

data "aws_subnet" "eks" {
  for_each = toset(data.aws_eks_cluster.existing.vpc_config[0].subnet_ids)
  id       = each.value
}

# These tags are set by the existing EKS/VPC modules; fail rather than fall back to public subnets/a broad SG.
data "aws_security_group" "eks_nodes" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.existing.id]
  }
  filter {
    name   = "tag:Name"
    values = ["${var.cluster_name}-node"]
  }
  filter {
    name   = "tag:kubernetes.io/cluster/${var.cluster_name}"
    values = ["owned"]
  }
}

locals {
  msk_private_subnets = [for id, subnet in data.aws_subnet.eks : id
    if !subnet.map_public_ip_on_launch && lookup(subnet.tags, "kubernetes.io/role/internal-elb", "") == "1"
  ]
}

resource "aws_security_group" "msk" {
  name_prefix = "itube-dev-msk-"
  description = "Private ITube MSK brokers"
  vpc_id      = data.aws_vpc.existing.id

  tags = { Name = "itube-dev-msk-brokers" }
}

resource "aws_vpc_security_group_ingress_rule" "msk_brokers" {
  security_group_id            = aws_security_group.msk.id
  referenced_security_group_id = aws_security_group.msk.id
  ip_protocol                  = "-1"
  description                  = "Communication between MSK-managed nodes only"
}

resource "aws_vpc_security_group_ingress_rule" "msk_eks_clients" {
  security_group_id            = aws_security_group.msk.id
  referenced_security_group_id = data.aws_security_group.eks_nodes.id
  ip_protocol                  = "tcp"
  from_port                    = 9098
  to_port                      = 9098
  description                  = "TLS/SASL-IAM from the existing EKS node security group"
}

resource "aws_vpc_security_group_egress_rule" "msk_vpc" {
  security_group_id = aws_security_group.msk.id
  cidr_ipv4         = data.aws_vpc.existing.cidr_block
  ip_protocol       = "-1"
  description       = "Outbound traffic is confined to the existing VPC"
}

# Only MSK uses these defaults; the existing local/in-cluster Kafka broker is unchanged.
resource "aws_msk_configuration" "itube" {
  name              = "itube-dev-msk"
  kafka_versions    = ["3.9.x"]
  server_properties = <<-PROPERTIES
    auto.create.topics.enable=false
    default.replication.factor=2
    min.insync.replicas=2
    offsets.topic.replication.factor=2
    transaction.state.log.replication.factor=2
    transaction.state.log.min.isr=2
  PROPERTIES
}

resource "aws_msk_cluster" "itube" {
  cluster_name           = "itube-dev-msk"
  kafka_version          = "3.9.x" # t3.small requires ZooKeeper; MSK does not support it with KRaft/4.x.
  number_of_broker_nodes = 2
  enhanced_monitoring    = "DEFAULT"

  configuration_info {
    arn      = aws_msk_configuration.itube.arn
    revision = aws_msk_configuration.itube.latest_revision
  }

  broker_node_group_info {
    instance_type   = "kafka.t3.small"
    client_subnets  = local.msk_private_subnets
    security_groups = [aws_security_group.msk.id]

    storage_info {
      ebs_storage_info {
        volume_size = 20 # GiB per broker; 40 GiB total.
      }
    }

    connectivity_info {
      public_access {
        type = "DISABLED"
      }
    }
  }

  client_authentication {
    unauthenticated = false
    sasl {
      iam = true
    }
  }

  encryption_info {
    # Encryption at rest uses the service's AWS-managed key; no customer KMS key or KMS policy is added.
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  lifecycle {
    precondition {
      condition     = var.region == "eu-west-2"
      error_message = "This MSK configuration is intended for London (eu-west-2), using the existing AWS provider."
    }

    precondition {
      condition     = length(local.msk_private_subnets) == 2 && length(toset([for id in local.msk_private_subnets : data.aws_subnet.eks[id].availability_zone])) == 2
      error_message = "The two-broker MSK cluster requires the existing VPC's two private subnets in two distinct AZs."
    }
  }
}

output "msk_cluster_arn" {
  description = "MSK ARN used by the dedicated clients' scoped IAM policies."
  value       = aws_msk_cluster.itube.arn
}

output "msk_bootstrap_brokers_sasl_iam" {
  description = "Private TLS/SASL-IAM endpoints on port 9098; applications are not switched to MSK by this file."
  value       = aws_msk_cluster.itube.bootstrap_brokers_sasl_iam
}

output "msk_security_group_id" {
  value = aws_security_group.msk.id
}

# These names are dedicated to the MSK clients. Creating the associations does not modify Deployments:
# a later workload change must create/select these service accounts and configure TLS/SASL-IAM.
# For Kafka UI, also enable readOnly and use group.id=kafka-ui-viewer if a browsing group is configured.
# MSK does not expose log-directory inspection through IAM; disable the UI's log-dir collection on migration.
# The local Docker UI is separate and does not receive EKS Pod Identity credentials.
locals {
  msk_client_service_accounts = {
    engagement = "engagement"
    kafka_ui   = "kafka-ui"
  }

  # Preserve the actual cluster UUID: permissions never span other MSK clusters or future replacements.
  msk_topic_arn_prefix = replace(aws_msk_cluster.itube.arn, ":cluster/", ":topic/")
  msk_group_arn_prefix = replace(aws_msk_cluster.itube.arn, ":cluster/", ":group/")

  # Match the current topic/group defaults in engagement-service; update this allowlist if names change.
  msk_engagement_write_topics = ["engagement.commands.v1", "engagement.commands.v1-dlt", "video.deleted-dlt"]
  msk_engagement_read_topics  = ["engagement.commands.v1", "video.deleted"]
  msk_engagement_groups       = ["itube-engagement-commands-v1", "itube-engagement-cleanup"]
}

resource "aws_iam_role" "msk_client" {
  for_each = local.msk_client_service_accounts

  name        = "itube-${var.cluster_name}-msk-${each.value}"
  description = "ITube ${var.namespace}/${each.value} MSK access through EKS Pod Identity"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = ["sts:AssumeRole", "sts:TagSession"]
      Principal = { Service = "pods.eks.amazonaws.com" }
      Condition = {
        StringEquals = {
          "aws:RequestTag/eks-cluster-arn"            = data.aws_eks_cluster.existing.arn
          "aws:RequestTag/kubernetes-namespace"       = var.namespace
          "aws:RequestTag/kubernetes-service-account" = each.value
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "msk_engagement" {
  name = "itube-msk-engagement"
  role = aws_iam_role.msk_client["engagement"].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid = "ConnectAndProduceIdempotently"
        Action = [
          "kafka-cluster:Connect", "kafka-cluster:DescribeCluster", "kafka-cluster:WriteDataIdempotently"
        ]
        Effect   = "Allow"
        Resource = [aws_msk_cluster.itube.arn]
      },
      {
        # Spring Kafka declares its own topics and may reconcile partitions/topic retention at startup.
        Sid = "ManageAndWriteOwnedTopics"
        Action = [
          "kafka-cluster:CreateTopic", "kafka-cluster:DescribeTopic", "kafka-cluster:WriteData",
          "kafka-cluster:AlterTopic", "kafka-cluster:DescribeTopicDynamicConfiguration",
          "kafka-cluster:AlterTopicDynamicConfiguration"
        ]
        Effect   = "Allow"
        Resource = [for topic in local.msk_engagement_write_topics : "${local.msk_topic_arn_prefix}/${topic}"]
      },
      {
        Sid      = "ReadCommandsAndVideoDeletionEvents"
        Action   = ["kafka-cluster:DescribeTopic", "kafka-cluster:ReadData"]
        Effect   = "Allow"
        Resource = [for topic in local.msk_engagement_read_topics : "${local.msk_topic_arn_prefix}/${topic}"]
      },
      {
        Sid      = "UseEngagementConsumerGroups"
        Action   = ["kafka-cluster:DescribeGroup", "kafka-cluster:AlterGroup"]
        Effect   = "Allow"
        Resource = [for group in local.msk_engagement_groups : "${local.msk_group_arn_prefix}/${group}"]
      }
    ]
  })
}

resource "aws_iam_role_policy" "msk_kafka_ui" {
  name = "itube-msk-kafka-ui-readonly"
  role = aws_iam_role.msk_client["kafka_ui"].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid = "InspectCluster"
        Action = [
          "kafka-cluster:Connect", "kafka-cluster:DescribeCluster", "kafka-cluster:DescribeClusterDynamicConfiguration"
        ]
        Effect   = "Allow"
        Resource = [aws_msk_cluster.itube.arn]
      },
      {
        Sid = "BrowseTopicsAndMessages"
        Action = [
          "kafka-cluster:DescribeTopic", "kafka-cluster:DescribeTopicDynamicConfiguration", "kafka-cluster:ReadData"
        ]
        Effect   = "Allow"
        Resource = ["${local.msk_topic_arn_prefix}/*"]
      },
      {
        Sid      = "InspectConsumerGroups"
        Action   = ["kafka-cluster:DescribeGroup"]
        Effect   = "Allow"
        Resource = ["${local.msk_group_arn_prefix}/*"]
      },
      {
        # ReadData's consumer-group dependency is confined to UI browsing groups, never application groups.
        # There are no topic writes/deletes/config edits, group deletes or application offset-reset grants.
        Sid      = "UseOnlyUiBrowsingGroups"
        Action   = ["kafka-cluster:AlterGroup"]
        Effect   = "Allow"
        Resource = ["${local.msk_group_arn_prefix}/kafka-ui-*"]
      }
    ]
  })
}

resource "aws_eks_pod_identity_association" "msk_client" {
  for_each = local.msk_client_service_accounts

  cluster_name         = data.aws_eks_cluster.existing.name
  namespace            = var.namespace
  service_account      = each.value
  role_arn             = aws_iam_role.msk_client[each.key].arn
  disable_session_tags = false # Required by the role's namespace/service-account/cluster trust conditions.
}

output "msk_client_roles" {
  description = "Dedicated role bindings for a later, separately reviewed EKS workload migration."
  value = {
    for client, role in aws_iam_role.msk_client : client => {
      role_arn        = role.arn
      namespace       = var.namespace
      service_account = local.msk_client_service_accounts[client]
    }
  }
}
