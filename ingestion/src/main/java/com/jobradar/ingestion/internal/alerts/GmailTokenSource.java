package com.jobradar.ingestion.internal.alerts;

/** Reads a user's Gmail refresh token. The production implementation uses Secrets Manager. */
interface GmailTokenSource {

    /** @throws GmailAccessException when no token is stored under {@code ref} */
    String refreshToken(String ref);
}
