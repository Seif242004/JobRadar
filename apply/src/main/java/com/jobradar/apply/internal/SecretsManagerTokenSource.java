package com.jobradar.apply.internal;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import tools.jackson.databind.json.JsonMapper;

/** The secret holds {@code {"refresh_token": "..."}}. The value is never logged. */
@Component
class SecretsManagerTokenSource implements TokenSource {

    private final SecretsManagerClient secrets;
    private final JsonMapper json;

    SecretsManagerTokenSource(SecretsManagerClient secrets, JsonMapper json) {
        this.secrets = secrets;
        this.json = json;
    }

    @Override
    public String refreshToken(String ref) {
        try {
            String secret = secrets.getSecretValue(request -> request.secretId(ref)).secretString();
            String token = json.readTree(secret).path("refresh_token").asString("");
            if (token.isBlank()) {
                throw new GmailNotConnectedException("The stored Gmail secret has no refresh token");
            }
            return token;
        } catch (ResourceNotFoundException e) {
            throw new GmailNotConnectedException("No Gmail secret is stored for this user");
        }
    }
}
