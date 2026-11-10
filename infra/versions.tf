terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # State is local for now. Move it to an S3 backend before a second person runs Terraform.
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = "jobradar"
      ManagedBy = "terraform"
    }
  }
}
