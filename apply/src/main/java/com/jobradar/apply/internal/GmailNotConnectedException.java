package com.jobradar.apply.internal;

/** The user's Gmail token is missing, expired or revoked: only the user can fix that. */
public class GmailNotConnectedException extends RuntimeException {

    public GmailNotConnectedException(String message) {
        super(message);
    }
}
