package com.jobradar.ingestion;

/** The two work queues. A backlog flood can never delay a fresh posting. */
public enum PostingQueue {
    /** Postings under 2 hours old. */
    HOT,
    /** Everything else. */
    BACKLOG
}
