package com.jobradar.apply.internal;

/** Reads a stored CV PDF. */
public interface CvPdfReader {

    byte[] read(String key);
}
