package com.jobradar.ingestion;

import java.time.Instant;

/**
 * Queue message published once for every new posting (SPEC.md, section 3).
 *
 * @param postingId   {@code posting.id}
 * @param firstSeenAt when JobRadar first saw the posting
 * @param postedAt    publication time from the source, or null when the source gives none
 */
public record PostingIngested(long postingId, Instant firstSeenAt, Instant postedAt) {}
