package com.jobradar.notifier.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Presigned GET links: the bucket stays private and the link expires on its own. */
@Component
@EnableConfigurationProperties(NotifierProperties.class)
class S3CvLinks implements CvLinks {

    private final S3Presigner presigner;
    private final NotifierProperties properties;

    S3CvLinks(S3Presigner presigner, NotifierProperties properties) {
        this.presigner = presigner;
        this.properties = properties;
    }

    @Override
    public String presign(String key) {
        return presigner
                .presignGetObject(request -> request
                        .signatureDuration(properties.cvLinkTtl())
                        .getObjectRequest(get -> get.bucket(properties.cvBucket()).key(key)))
                .url()
                .toString();
    }
}
