package com.jobradar.ingestion.internal.alerts;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;

/** Loads the synthetic {@code .eml} fixtures (see fixtures/README.md). */
final class Emails {

    static final Instant RECEIVED = Instant.parse("2026-11-11T07:30:00Z");

    private Emails() {}

    static byte[] bytes(String name) {
        try (InputStream in = Emails.class.getResourceAsStream("/fixtures/gmail/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static AlertEmail load(String name) {
        return MailParser.parse("msg-" + name, bytes(name), RECEIVED);
    }
}
