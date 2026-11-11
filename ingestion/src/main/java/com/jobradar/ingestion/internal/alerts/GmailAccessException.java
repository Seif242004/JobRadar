package com.jobradar.ingestion.internal.alerts;

/**
 * One user's Gmail cannot be read: no token stored, the token was revoked, or Gmail refused the
 * request. The user is skipped for this run; other users and sources are unaffected.
 */
class GmailAccessException extends RuntimeException {

    GmailAccessException(String message) {
        super(message);
    }
}
