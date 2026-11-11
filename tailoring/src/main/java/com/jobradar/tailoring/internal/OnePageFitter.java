package com.jobradar.tailoring.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

/**
 * The page-fit step of Flow 3: keep as many of the highest-ranked bullets as fit on one page.
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
        // The page count only grows as bullets are added, so the longest prefix of the ranked list
        // that still fits on one page can be found by binary search (about 7 renders for 78 bullets).
        Fitted best = null;
        int low = 1;
        int high = rankedIds.size();
        while (low <= high) {
            int middle = (low + high) >>> 1;
            List<Long> ids = List.copyOf(rankedIds.subList(0, middle));
            CvDocument document = CvComposer.compose(data, ids, summary);
            byte[] pdf = renderer.render(document);
            if (pageCount(pdf) == 1) {
                best = new Fitted(pdf, document, ids);
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        if (best == null) {
            throw new IllegalStateException("The CV does not fit on one page even without bullets");
        }
        return best;
    }

    static int pageCount(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            throw new UncheckedIOException("Rendered CV is not a readable PDF", e);
        }
    }
}
