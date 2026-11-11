package com.jobradar.tailoring.internal;

/** Renders a CV document to PDF bytes. The production implementation runs Typst. */
public interface PdfRenderer {

    byte[] render(CvDocument document);
}
