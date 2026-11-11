package com.jobradar.cvscore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/** Text extraction of an uploaded CV; scoring itself is covered by AtsScorerTests. */
class BaseCvsTests {

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void textIsExtractedFromAPdf() throws Exception {
        assertThat(BaseCvs.extractText(pdf("Java Spring Boot PostgreSQL"))).contains("Spring Boot");
    }

    @Test
    void somethingThatIsNotAPdfIsRejected() {
        assertThatThrownBy(() -> BaseCvs.extractText("hello".getBytes())).isInstanceOf(IllegalArgumentException.class);
    }
}
