package com.jobradar.notifier.internal;

import com.jobradar.notifier.CvLinkSigner;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * CV links for notifications. With {@code jobradar.public-url} set the link goes through the
 * dashboard and carries an HMAC signature, so it opens on a phone; without it the link is an S3
 * presigned URL, which only works on the machine that runs the bucket.
 */
@Component
@EnableConfigurationProperties(NotifierProperties.class)
class S3CvLinks implements CvLinks {

    private final S3Presigner presigner;
    private final NotifierProperties properties;
    private final CvLinkSigner signer;
    private final String publicUrl;

    S3CvLinks(
            S3Presigner presigner,
            NotifierProperties properties,
            CvLinkSigner signer,
            @Value("${jobradar.public-url:}") String publicUrl) {
        this.presigner = presigner;
        this.properties = properties;
        this.signer = signer;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    @Override
    public String presign(String key) {
        if (!publicUrl.isBlank() && signer.enabled()) {
            return publicUrl + "/api/dashboard/cv?k=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                    + "&t=" + signer.sign(key, properties.cvLinkTtl());
        }
        return presigner
                .presignGetObject(request -> request
                        .signatureDuration(properties.cvLinkTtl())
                        .getObjectRequest(get -> get.bucket(properties.cvBucket()).key(key)))
                .url()
                .toString();
    }
}
