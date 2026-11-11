package com.jobradar.ingestion.internal.alerts;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import tools.jackson.databind.json.JsonMapper;

/** The secret holds {@code {"refresh_token": "..."}}, written by {@code scripts/gmail_authorize.py}. Never logged. */
@Component
class SecretsManagerGmailTokenSource implements GmailTokenSource {

    private final SecretsManagerClient secrets;
    private final JsonMapper json;

    SecretsManagerGmailTokenSource(SecretsManagerClient secrets, JsonMapper json) {
        this.secrets = secrets;
        this.json = json;
    }

    @Override
    public String refreshToken(String ref) {
        try {
            String secret = secrets.getSecretValue(request -> request.secretId(ref)).secretString();
            String token = json.readTree(secret).path("refresh_token").asString("");
            if (token.isBlank()) {
                throw new GmailAccessException("The stored Gmail secret has no refresh token");
            }
            return token;
        } catch (ResourceNotFoundException e) {
            throw new GmailAccessException("No Gmail secret is stored for this user");
        }
    }
}
