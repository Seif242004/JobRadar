package com.jobradar.apply.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailComposerTests {

    private static ApplyContext context(String title, String company, String name) {
        return new ApplyContext(title, company, "email", "hr@example.com", "me@example.com", "ref", name, 5, "cv/1/2/x.pdf");
    }

    @Test
    void fillsTheTemplateFromThePostingAndTheUsersOwnName() {
        OutgoingEmail email = EmailComposer.compose(context("Backend Intern", "Instabug", "Seif Tamer"), "hr@example.com", new byte[] {1});

        assertThat(email.from()).isEqualTo("me@example.com");
        assertThat(email.to()).isEqualTo("hr@example.com");
        assertThat(email.subject()).isEqualTo("Application: Backend Intern - Seif Tamer");
        assertThat(email.body()).contains("apply for the Backend Intern at Instabug position", "Best regards,\nSeif Tamer\nme@example.com");
        assertThat(email.attachmentName()).isEqualTo("Seif_Tamer_CV.pdf");
        assertThat(email.attachment()).containsExactly(1);
    }

    @Test
    void worksWithoutCompanyOrName() {
        OutgoingEmail email = EmailComposer.compose(context("Engineer", null, null), "hr@example.com", new byte[0]);

        assertThat(email.subject()).isEqualTo("Application: Engineer");
        assertThat(email.body()).contains("apply for the Engineer position");
        assertThat(email.attachmentName()).isEqualTo("CV.pdf");
    }

    @Test
    void aPostingTitleCannotInjectHeaders() {
        OutgoingEmail email = EmailComposer.compose(
                context("Intern\r\nBcc: victim@example.com", "Acme\nCo", "Seif"), "hr@example.com", new byte[0]);

        assertThat(email.subject()).doesNotContain("\r", "\n");
        assertThat(email.subject()).isEqualTo("Application: Intern Bcc: victim@example.com - Seif");
    }
}
