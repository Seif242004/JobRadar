package com.jobradar.apply;

/**
 * Result of an approval, shown to the user.
 *
 * @param detail a short human-readable reason, never a stack trace or a secret
 */
public record ApplyOutcome(Status status, String detail) {

    public enum Status {
        /** The email was sent and the application recorded. */
        SENT,
        /** The user applied elsewhere and told the bot; nothing was sent. */
        MARKED_APPLIED,
        /** An application for this posting already exists; nothing was sent. */
        ALREADY_APPLIED,
        /** The daily limit of applications was reached; nothing was sent. */
        CAP_REACHED,
        /** The posting has no email address to apply to. */
        NO_EMAIL_TARGET,
        /** The user has not connected Gmail. */
        GMAIL_NOT_CONNECTED,
        /** The posting, CV or user does not exist or does not belong together. */
        NOT_FOUND,
        /** Sending failed; nothing was recorded, so the user can try again. */
        FAILED
    }

    static ApplyOutcome of(Status status, String detail) {
        return new ApplyOutcome(status, detail);
    }
}
