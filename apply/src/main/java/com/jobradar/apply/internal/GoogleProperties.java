package com.jobradar.apply.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code jobradar.google.*}: the OAuth client of the Google Cloud project and the API endpoints.
 * The client secret comes from Secrets Manager through an environment variable; user tokens are
 * never configuration, they live in Secrets Manager under {@code app_user.gmail_token_ref}.
 */
@ConfigurationProperties("jobradar.google")
public record GoogleProperties(
        @DefaultValue("") String clientId,
        @DefaultValue("") String clientSecret,
        @DefaultValue("https://oauth2.googleapis.com/token") String tokenUrl,
        @DefaultValue("https://gmail.googleapis.com/gmail/v1") String gmailUrl) {}
