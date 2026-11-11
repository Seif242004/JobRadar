package com.jobradar.tailoring.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

/**
 * The page-fit loop of Flow 3: render; if the CV spills past one page, drop the lowest-ranked
 * bullet and render again.
 */
@Component
public class OnePageFitter {

    /**
     * @param bulletIds the bullets that are on the page, in rank order
     */
    public record Fitted(byte[] pdf, CvDocument document, List<Long> bulletIds) {}

    private final PdfRenderer renderer;

    OnePageFitter(PdfRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * @param rankedIds validated bullet IDs, most relevant first
     * @throws IllegalStateException when even a single bullet does not fit on one page
     */
    public Fitted fit(TailoringData data, List<Long> rankedIds) {
        return fit(data, rankedIds, null);
    }

    /** @param summary the summary line to print under the header, or null */
    public Fitted fit(TailoringData data, List<Long> rankedIds, String summary) {
        List<Long> ids = new ArrayList<>(rankedIds);
        while (!ids.isEmpty()) {
            CvDocument document = CvComposer.compose(data, ids, summary);
            byte[] pdf = renderer.render(document);
            if (pageCount(pdf) == 1) {
                return new Fitted(pdf, document, List.copyOf(ids));
            }
            // The list is ordered by relevance, so the last element is the least relevant.
            ids.remove(ids.size() - 1);
        }
        throw new IllegalStateException("The CV does not fit on one page even without bullets");
    }

    static int pageCount(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            throw new UncheckedIOException("Rendered CV is not a readable PDF", e);
        }
    }
}
