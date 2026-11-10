package com.jobradar.ingestion;

import java.util.List;

/**
 * Reads postings from one official or public source (ATS feed, RSS, or the user's Gmail label).
 *
 * <p>Guardrails: an implementation must never scrape or automate LinkedIn or Wuzzuf, must never
 * require a login to a third-party site, and must respect its source's rate limit in its schedule.
 */
public interface SourceConnector {

    /** Stable connector name, stored in {@code posting.source} and {@code source_run.source}. */
    String source();

    /**
     * Fetches the postings currently visible in the source. May return postings seen before;
     * {@link IngestionService} drops those.
     */
    List<RawPosting> fetch();
}
