package com.jobradar.tailoring.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/** PDFs live under {@code cv/<user>/<posting>/}; the per-user prefix makes one-click delete simple. */
@Component
class S3CvStore implements CvStore {

    private final S3Client s3;
    private final TailoringProperties properties;

    S3CvStore(S3Client s3, TailoringProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    @Override
    public String put(long userId, long postingId, byte[] pdf) {
        // A random suffix keeps a re-render from overwriting a PDF that an application already used.
        String key = "cv/" + userId + "/" + postingId + "/" + UUID.randomUUID() + ".pdf";
        s3.putObject(
                request -> request.bucket(properties.bucket()).key(key).contentType("application/pdf"),
                RequestBody.fromBytes(pdf));
        return key;
    }
}
