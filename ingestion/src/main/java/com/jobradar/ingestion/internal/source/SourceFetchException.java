package com.jobradar.ingestion.internal.source;

/** A source could not be read: no answer, an error status, or a body that is not the expected JSON. */
public class SourceFetchException extends RuntimeException {

    public SourceFetchException(String message) {
        super(message);
    }

    public SourceFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
