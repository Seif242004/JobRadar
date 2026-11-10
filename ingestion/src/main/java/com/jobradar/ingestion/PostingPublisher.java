package com.jobradar.ingestion;

/** Publishes new postings to the work queues. The production implementation uses SQS. */
public interface PostingPublisher {

    void publish(PostingIngested event, PostingQueue queue);
}
