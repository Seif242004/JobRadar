locals {
  # One repository per deployable (docs/PLAN.md, "Three deployables").
  deployables = toset(["api", "ingestion-worker", "tailoring-worker"])
}

resource "aws_ecr_repository" "app" {
  for_each = local.deployables

  name                 = "${var.name}/${each.key}"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

# Keep only the 10 newest images per repository so storage stays inside the budget.
resource "aws_ecr_lifecycle_policy" "app" {
  for_each = aws_ecr_repository.app

  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the 10 newest images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}
