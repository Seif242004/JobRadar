package com.jobradar.ingestion.internal.alerts;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code jobradar.ingestion.gmail.*}. The OAuth client is the same one the apply flow uses
 * ({@code JOBRADAR_GOOGLE_CLIENT_ID} and {@code JOBRADAR_GOOGLE_CLIENT_SECRET}). Users' refresh
 * tokens are never configuration; they live in Secrets Manager under {@code app_user.gmail_token_ref}.
 *
 * @param enabled         turns the reader off entirely
 * @param label           the one Gmail label whose messages are read (a Gmail filter puts the alert emails there)
 * @param lookbackDays    how far back in that label a run looks; restarts re-read this window once
 * @param maxMessagesPerRun upper bound on messages fetched per user and run
 */
@ConfigurationProperties("jobradar.ingestion.gmail")
record GmailAlertProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("JobRadar") String label,
        @DefaultValue("7") int lookbackDays,
        @DefaultValue("300") int maxMessagesPerRun,
        @DefaultValue("") String clientId,
        @DefaultValue("") String clientSecret,
        @DefaultValue("https://oauth2.googleapis.com/token") String tokenUrl,
        @DefaultValue("https://gmail.googleapis.com/gmail/v1") String apiUrl) {

    boolean clientConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }
}
