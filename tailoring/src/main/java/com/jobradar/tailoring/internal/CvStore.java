package com.jobradar.tailoring.internal;

/** Stores a rendered CV and returns its key ({@code cv_version.pdf_s3_key}). */
public interface CvStore {

    String put(long userId, long postingId, byte[] pdf);
}
