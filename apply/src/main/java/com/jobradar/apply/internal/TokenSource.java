package com.jobradar.apply.internal;

/** Reads a user's Gmail refresh token. The production implementation uses Secrets Manager. */
public interface TokenSource {

    /** @throws GmailNotConnectedException when no token is stored under {@code ref} */
    String refreshToken(String ref);
}
