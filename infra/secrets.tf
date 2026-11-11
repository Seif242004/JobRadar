# Secret shells only. Terraform creates the empty secrets; their values are set by hand, so they
# never enter Terraform state or the repository:
#   aws secretsmanager put-secret-value --secret-id jobradar/ai-key --secret-string '<key>'
#   aws secretsmanager put-secret-value --secret-id jobradar/telegram-bot-token --secret-string '<token>'
#   aws secretsmanager put-secret-value --secret-id jobradar/google-client-secret --secret-string '<secret>'
# Each user's Gmail refresh token is stored by the consent step as {"refresh_token": "..."} under
# jobradar/gmail/<user id>; app_user.gmail_token_ref holds that secret id.

resource "aws_secretsmanager_secret" "ai_key" {
  name        = "${var.name}/ai-key"
  description = "NVIDIA Build API key (JOBRADAR_AI_KEY)"
}

resource "aws_secretsmanager_secret" "telegram_bot_token" {
  name        = "${var.name}/telegram-bot-token"
  description = "Telegram bot token (JOBRADAR_TELEGRAM_BOT_TOKEN)"
}

resource "aws_secretsmanager_secret" "google_client_secret" {
  name        = "${var.name}/google-client-secret"
  description = "Google OAuth client secret (JOBRADAR_GOOGLE_CLIENT_SECRET)"
}
