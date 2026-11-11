variable "aws_region" {
  description = "AWS region for every resource (SPEC.md D5)."
  type        = string
  default     = "eu-central-1"
}

variable "name" {
  description = "Prefix for resource names."
  type        = string
  default     = "jobradar"
}

variable "vpc_cidr" {
  description = "CIDR block of the VPC."
  type        = string
  default     = "10.20.0.0/16"
}

variable "github_repository" {
  description = "GitHub repository (owner/name) allowed to assume the deploy role through OIDC."
  type        = string
  default     = "your-github-user/jobradar"
}

variable "create_github_oidc_provider" {
  description = "Set to false if this AWS account already has the GitHub Actions OIDC provider."
  type        = bool
  default     = true
}

variable "db_instance_class" {
  description = "RDS instance class. One small instance (cost guard)."
  type        = string
  default     = "db.t4g.micro"
}

variable "budget_limit_usd" {
  description = "Monthly cost budget in USD; an alert is sent at 80% and 100%."
  type        = string
  default     = "5"
}

variable "budget_alert_email" {
  description = "Email address that receives the budget alerts."
  type        = string
}

variable "image_tag" {
  description = "Image tag the ECS task definitions run (ECR tags are immutable, so use the commit SHA that CI pushed)."
  type        = string
  default     = "unset"
}

variable "deploy_services" {
  description = "Run one task per service. Keep false until images exist in ECR and the secrets have values."
  type        = bool
  default     = false
}

variable "google_client_id" {
  description = "Client ID of the Google OAuth client used to send applications from the users' Gmail. Not a secret."
  type        = string
  default     = ""
}
