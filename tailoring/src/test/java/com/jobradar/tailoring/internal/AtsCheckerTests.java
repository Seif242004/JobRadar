package com.jobradar.tailoring.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class AtsCheckerTests {

    private static byte[] pdf(int pages, String... lines) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int p = 0; p < pages; p++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    content.setLeading(12);
                    content.newLineAtOffset(40, 740);
                    for (String line : lines) {
                        content.showText(line);
                        content.newLine();
                    }
                    content.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static TailoringData data(String title, String description) {
        return new TailoringData(1, 1, title, "Co", "Cairo", description,
                new CvStatic("Jane Doe", "B.Sc.", List.of(), List.of(), null), List.of(), List.of(), List.of());
    }

    private static CvDocument document(String summary) {
        return new CvDocument("Jane Doe", "B.Sc.", summary,
                List.of(new CvDocument.Contact("jane@example.com", "mailto:jane@example.com")),
                List.of(CvDocumentSections.experience()));
    }

    /** Test-only access to the package-private section factory. */
    private static final class CvDocumentSections {
        static CvDocument.Section experience() {
            return new CvDocument.Section("Experience", "entries", List.of(), List.of(), List.of());
        }
    }

    @Test
    void aReadableOnePageCvPassesAndReportsMatchedAndMissingKeywords() throws Exception {
        String filler = "Built Java Spring services with PostgreSQL for partner teams, delivering reliable releases weekly. ".repeat(3);
        byte[] pdf = pdf(1, "Jane Doe", "jane@example.com", "EXPERIENCE", filler, "Java Spring Docker");
        TailoringData data = data("Java Backend Intern", "Java Spring Kubernetes Java Spring Kubernetes");

        AtsReport report = AtsChecker.check(data, document("A summary."), pdf,
                List.of(new ProfileBullet(7, "experience", "Deployed Kubernetes clusters", false, null)), List.of());

        assertThat(report.matched()).contains("java", "spring");
        assertThat(report.missing()).contains("kubernetes");
        assertThat(report.checks()).extracting(AtsReport.Check::name)
                .contains("Selectable text", "Contact details", "Standard headings", "One page", "Role summary", "Keyword coverage");
        assertThat(report.checks().stream().filter(c -> c.name().equals("One page")).findFirst().orElseThrow().ok()).isTrue();
        // The missing keyword is in the profile, so the fix points at it rather than inviting a new claim.
        assertThat(report.fixes()).anyMatch(f -> f.contains("already mentions kubernetes"));
    }

    @Test
    void aTwoPageCvAndAMissingSummaryAreFlagged() throws Exception {
        byte[] pdf = pdf(2, "Jane Doe", "jane@example.com", "EXPERIENCE");

        AtsReport report = AtsChecker.check(data("Intern", ""), document(null), pdf, List.of(), List.of());

        assertThat(report.checks().stream().filter(c -> c.name().equals("One page")).findFirst().orElseThrow().ok()).isFalse();
        assertThat(report.checks().stream().filter(c -> c.name().equals("Role summary")).findFirst().orElseThrow().ok()).isFalse();
        assertThat(report.fixes()).anyMatch(f -> f.startsWith("One page"));
    }
}
