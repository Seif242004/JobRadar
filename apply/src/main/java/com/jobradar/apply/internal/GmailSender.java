package com.jobradar.apply.internal;

/** Sends an email from the user's Gmail account. Public so api tests can replace it. */
public interface GmailSender {

    /**
     * @param tokenRef Secrets Manager id of the user's Gmail refresh token
     * @throws GmailNotConnectedException when the token is missing or was revoked
     */
    void send(String tokenRef, OutgoingEmail email);
}
