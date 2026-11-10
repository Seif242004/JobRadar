package com.jobradar.apply.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MailTargetTests {

    @Test
    void readsPlainAddressesAndMailtoLinks() {
        assertThat(MailTarget.parse("hr@example.com")).contains("hr@example.com");
        assertThat(MailTarget.parse(" jobs+eg@acme-corp.co.uk ")).contains("jobs+eg@acme-corp.co.uk");
        assertThat(MailTarget.parse("mailto:hr@example.com?subject=Backend%20Intern")).contains("hr@example.com");
        assertThat(MailTarget.parse("MAILTO:hr@example.com")).contains("hr@example.com");
    }

    @Test
    void rejectsAnythingElse() {
        assertThat(MailTarget.parse(null)).isEmpty();
        assertThat(MailTarget.parse("https://example.com/apply")).isEmpty();
        assertThat(MailTarget.parse("hr@example.com, boss@example.com")).isEmpty();
        assertThat(MailTarget.parse("Jane <hr@example.com>")).isEmpty();
        assertThat(MailTarget.parse("hr@example.com\nBcc: x@evil.com")).isEmpty();
        assertThat(MailTarget.parse("no-at-sign.example.com")).isEmpty();
    }
}
