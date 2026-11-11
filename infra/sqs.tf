# Queues of the pipeline (docs/PLAN.md, SPEC.md "Queue messages"). Every work queue redrives to one
# dead-letter queue after 3 failed receives, so a poison message never blocks the others.

resource "aws_sqs_queue" "dlq" {
  name                      = "${var.name}-dlq"
  message_retention_seconds = 1209600 # 14 days, the maximum, so failures can be inspected later
  sqs_managed_sse_enabled   = true
}

locals {
  # visibility = seconds a received message stays hidden. It must exceed the longest handling time.
  work_queues = {
    "hot"            = 60  # PostingIngested, postings under 2 hours old
    "backlog"        = 60  # PostingIngested, all other postings
    "tailor-hot"     = 180 # TailorRequested, Hot matches (Typst renders up to a few times per request)
    "tailor-backlog" = 180 # TailorRequested, Warm matches
    "cv-ready"       = 60  # CvReady, from the tailoring-worker to the notifier
  }
}

resource "aws_sqs_queue" "work" {
  for_each = local.work_queues

  name                       = "${var.name}-${each.key}"
  visibility_timeout_seconds = each.value
  message_retention_seconds  = 345600 # 4 days
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq.arn
    maxReceiveCount     = 3
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  queue_url = aws_sqs_queue.dlq.id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [for queue in aws_sqs_queue.work : queue.arn]
  })
}
