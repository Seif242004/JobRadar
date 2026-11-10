package com.jobradar.tailoring.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code jobradar.tailoring.*}.
 *
 * @param typstBinary path or name of the Typst executable
 * @param bucket      S3 bucket for generated PDFs (under {@code cv/})
 */
@ConfigurationProperties("jobradar.tailoring")
public record TailoringProperties(@DefaultValue("typst") String typstBinary, String bucket, Queues queues) {

    public record Queues(String tailorHotUrl, String tailorBacklogUrl, String cvReadyUrl) {}
}
