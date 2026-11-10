# Secret shells only. Terraform creates the empty secrets; their values are set by hand, so they
# never enter Terraform state or the repository:
#   aws secretsmanager put-secret-value --secret-id jobradar/ai-key --secret-string '<key>'
#   aws secretsmanager put-secret-value --secret-id jobradar/telegram-bot-token --secret-string '<token>'

resource "aws_secretsmanager_secret" "ai_key" {
  name        = "${var.name}/ai-key"
  description = "NVIDIA Build API key (JOBRADAR_AI_KEY)"
}

resource "aws_secretsmanager_secret" "telegram_bot_token" {
  name        = "${var.name}/telegram-bot-token"
  description = "Telegram bot token (JOBRADAR_TELEGRAM_BOT_TOKEN)"
}
