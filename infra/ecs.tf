# Runtime of the three deployables on ECS Fargate (SPEC.md D5, D9). Tasks run in the public
# subnets with a public IP, because there is no NAT gateway. The application security group has
# no ingress rule, so nothing can connect to a task. There is no load balancer: the api only
# consumes queues and runs scheduled jobs until the dashboard exists (M4).
#
# Services start at 0 tasks. Set deploy_services = true after the first images were pushed to ECR
# and the two secrets in secrets.tf have values.

locals {
  services = {
    api = {
      cpu    = 256
      memory = 512
    }
    ingestion-worker = {
      cpu    = 256
      memory = 512
    }
    # Typst renders a CV a few times per request, so this one gets more room.
    tailoring-worker = {
      cpu    = 512
      memory = 1024
    }
  }

  queue_urls = { for key, queue in aws_sqs_queue.work : key => queue.url }

  db_url = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${aws_db_instance.main.db_name}"

  common_environment = [
    { name = "AWS_REGION", value = var.aws_region },
    { name = "JOBRADAR_S3_BUCKET", value = aws_s3_bucket.data.bucket },
    { name = "JOBRADAR_DB_URL", value = local.db_url },
    { name = "JOBRADAR_DB_USER", value = aws_db_instance.main.username },
  ]

  db_password_secret = {
    name      = "JOBRADAR_DB_PASSWORD"
    valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::"
  }
  ai_key_secret = {
    name      = "JOBRADAR_AI_KEY"
    valueFrom = aws_secretsmanager_secret.ai_key.arn
  }

  # Per service: extra environment variables and secrets.
  service_environment = {
    api = [
      { name = "JOBRADAR_SQS_HOT_URL", value = local.queue_urls["hot"] },
      { name = "JOBRADAR_SQS_BACKLOG_URL", value = local.queue_urls["backlog"] },
      { name = "JOBRADAR_SQS_TAILOR_HOT_URL", value = local.queue_urls["tailor-hot"] },
      { name = "JOBRADAR_SQS_TAILOR_BACKLOG_URL", value = local.queue_urls["tailor-backlog"] },
      { name = "JOBRADAR_SQS_CV_READY_URL", value = local.queue_urls["cv-ready"] },
      { name = "JOBRADAR_GOOGLE_CLIENT_ID", value = var.google_client_id },
    ]
    ingestion-worker = [
      { name = "JOBRADAR_SQS_HOT_URL", value = local.queue_urls["hot"] },
      { name = "JOBRADAR_SQS_BACKLOG_URL", value = local.queue_urls["backlog"] },
      # Connectors poll on their own schedules inside this one task.
      { name = "JOBRADAR_INGESTION_SCHEDULING_ENABLED", value = "true" },
      # Gmail alert reader (the user's own label, read-only).
      { name = "JOBRADAR_GOOGLE_CLIENT_ID", value = var.google_client_id },
    ]
    tailoring-worker = [
      { name = "JOBRADAR_SQS_TAILOR_HOT_URL", value = local.queue_urls["tailor-hot"] },
      { name = "JOBRADAR_SQS_TAILOR_BACKLOG_URL", value = local.queue_urls["tailor-backlog"] },
      { name = "JOBRADAR_SQS_CV_READY_URL", value = local.queue_urls["cv-ready"] },
    ]
  }
  service_secrets = {
    api              = [local.db_password_secret, local.ai_key_secret, { name = "JOBRADAR_TELEGRAM_BOT_TOKEN", valueFrom = aws_secretsmanager_secret.telegram_bot_token.arn }, { name = "JOBRADAR_GOOGLE_CLIENT_SECRET", valueFrom = aws_secretsmanager_secret.google_client_secret.arn }]
    ingestion-worker = [local.db_password_secret, { name = "JOBRADAR_GOOGLE_CLIENT_SECRET", valueFrom = aws_secretsmanager_secret.google_client_secret.arn }]
    tailoring-worker = [local.db_password_secret, local.ai_key_secret]
  }
}

resource "aws_ecs_cluster" "main" {
  name = var.name
}

resource "aws_cloudwatch_log_group" "app" {
  for_each = local.services

  name              = "/${var.name}/${each.key}"
  retention_in_days = 14
}

# ---------------------------------------------------------------- IAM

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# The execution role pulls images, writes logs and injects secrets into the container.
resource "aws_iam_role" "execution" {
  name               = "${var.name}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secrets" {
  statement {
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      aws_db_instance.main.master_user_secret[0].secret_arn,
      aws_secretsmanager_secret.ai_key.arn,
      aws_secretsmanager_secret.telegram_bot_token.arn,
      aws_secretsmanager_secret.google_client_secret.arn,
    ]
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "read-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets.json
}

# One task role per deployable, each limited to the queues and S3 prefixes it uses.
resource "aws_iam_role" "task" {
  for_each = local.services

  name               = "${var.name}-${each.key}"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

locals {
  consume = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:ChangeMessageVisibility"]

  task_permissions = {
    api = [
      { actions = local.consume, resources = [for key in ["hot", "backlog", "cv-ready"] : aws_sqs_queue.work[key].arn] },
      { actions = ["sqs:SendMessage"], resources = [for key in ["tailor-hot", "tailor-backlog"] : aws_sqs_queue.work[key].arn] },
      # The api signs download links for generated CVs.
      { actions = ["s3:GetObject"], resources = ["${aws_s3_bucket.data.arn}/cv/*"] },
      # Users' Gmail refresh tokens, one secret per user under jobradar/gmail/.
      { actions = ["secretsmanager:GetSecretValue"], resources = ["arn:aws:secretsmanager:${var.aws_region}:${data.aws_caller_identity.current.account_id}:secret:${var.name}/gmail/*"] },
    ]
    ingestion-worker = [
      { actions = ["sqs:SendMessage"], resources = [for key in ["hot", "backlog"] : aws_sqs_queue.work[key].arn] },
      { actions = ["s3:PutObject"], resources = ["${aws_s3_bucket.data.arn}/raw/*"] },
      # Users' Gmail refresh tokens, to read the alert label.
      { actions = ["secretsmanager:GetSecretValue"], resources = ["arn:aws:secretsmanager:${var.aws_region}:${data.aws_caller_identity.current.account_id}:secret:${var.name}/gmail/*"] },
    ]
    tailoring-worker = [
      { actions = local.consume, resources = [for key in ["tailor-hot", "tailor-backlog"] : aws_sqs_queue.work[key].arn] },
      { actions = ["sqs:SendMessage"], resources = [aws_sqs_queue.work["cv-ready"].arn] },
      { actions = ["s3:PutObject"], resources = ["${aws_s3_bucket.data.arn}/cv/*"] },
    ]
  }
}

data "aws_iam_policy_document" "task" {
  for_each = local.services

  dynamic "statement" {
    for_each = local.task_permissions[each.key]

    content {
      actions   = statement.value.actions
      resources = statement.value.resources
    }
  }
}

resource "aws_iam_role_policy" "task" {
  for_each = local.services

  name   = "queues-and-s3"
  role   = aws_iam_role.task[each.key].id
  policy = data.aws_iam_policy_document.task[each.key].json
}

# ---------------------------------------------------------------- tasks and services

resource "aws_ecs_task_definition" "app" {
  for_each = local.services

  family                   = "${var.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = each.value.cpu
  memory                   = each.value.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task[each.key].arn

  runtime_platform {
    operating_system_family = "LINUX"
    # The Dockerfile build in CI runs on x86_64 runners.
    cpu_architecture = "X86_64"
  }

  container_definitions = jsonencode([{
    name      = each.key
    image     = "${aws_ecr_repository.app[each.key].repository_url}:${var.image_tag}"
    essential = true

    environment = concat(local.common_environment, local.service_environment[each.key])
    secrets     = local.service_secrets[each.key]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app[each.key].name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = each.key
      }
    }
  }])
}

resource "aws_ecs_service" "app" {
  for_each = local.services

  name            = each.key
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app[each.key].arn
  launch_type     = "FARGATE"
  desired_count   = var.deploy_services ? 1 : 0

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = true # no NAT gateway (SPEC.md D9)
  }
}
