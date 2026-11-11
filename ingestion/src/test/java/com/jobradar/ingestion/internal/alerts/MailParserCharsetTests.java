package com.jobradar.ingestion.internal.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MailParserCharsetTests {

    @Test
    void htmlPartWithoutCharsetIsReadAsUtf8() {
        String mail = "From: a@b.c\r\nSubject: x\r\nMIME-Version: 1.0\r\nContent-Type: text/html\r\n"
                + "Content-Transfer-Encoding: 8bit\r\n\r\n<p>Backend Engineer – .NET</p>\r\n";
        AlertEmail email = MailParser.parse("1", mail.getBytes(StandardCharsets.UTF_8), Instant.now());

        assertThat(email.html()).contains("Backend Engineer – .NET");
    }

    @Test
    void declaredCharsetIsHonoured() {
        String mail = "From: a@b.c\r\nSubject: x\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=ISO-8859-1\r\n"
                + "Content-Transfer-Encoding: 8bit\r\n\r\ncafé\r\n";
        AlertEmail email = MailParser.parse("2", mail.getBytes(StandardCharsets.ISO_8859_1), Instant.now());

        assertThat(email.text()).contains("café");
    }
}
