output "github_deploy_role_arn" {
  description = "Set this as the AWS_ROLE_ARN repository variable in GitHub."
  value       = aws_iam_role.github_deploy.arn
}

output "ecr_repository_urls" {
  description = "ECR repository URL per deployable."
  value       = { for name, repo in aws_ecr_repository.app : name => repo.repository_url }
}

output "db_endpoint" {
  description = "RDS endpoint (host:port)."
  value       = aws_db_instance.main.endpoint
}

output "db_master_secret_arn" {
  description = "Secrets Manager secret that holds the generated database password."
  value       = aws_db_instance.main.master_user_secret[0].secret_arn
}

output "data_bucket" {
  description = "S3 bucket for raw postings and generated PDFs."
  value       = aws_s3_bucket.data.bucket
}
