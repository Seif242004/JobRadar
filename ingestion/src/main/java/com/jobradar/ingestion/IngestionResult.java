package com.jobradar.ingestion;

/**
 * Outcome of ingesting one connector run.
 *
 * @param itemsFound postings the connector returned
 * @param newItems   postings stored and published
 * @param hot        how many of the new postings went to the hot queue
 * @param errors     postings that failed to store or publish
 */
public record IngestionResult(int itemsFound, int newItems, int hot, int errors) {}
