package com.jobradar.apply.internal;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;

@Component
class S3CvPdfReader implements CvPdfReader {

    private final S3Client s3;
    private final ApplyProperties properties;

    S3CvPdfReader(S3Client s3, ApplyProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    @Override
    public byte[] read(String key) {
        return s3.getObject(request -> request.bucket(properties.cvBucket()).key(key), ResponseTransformer.toBytes())
                .asByteArray();
    }
}
