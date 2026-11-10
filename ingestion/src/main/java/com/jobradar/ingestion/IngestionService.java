package com.jobradar.ingestion;

import com.jobradar.ingestion.internal.PostingAge;
import com.jobradar.ingestion.internal.PostingNormalizer;
import com.jobradar.ingestion.internal.PostingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Flow 1 of docs/PLAN.md: normalise, dedupe, store, publish.
 *
 * <p>Delivery is at-least-once. Each posting is stored and published inside one transaction: if
 * publishing fails the row is rolled back and the next run retries it. If the commit fails after
 * a successful publish, the queue holds a message for a posting that does not exist, which
 * consumers must ignore.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final PostingRepository postings;
    private final PostingPublisher publisher;
    private final TransactionTemplate transaction;
    private final Clock clock;

    IngestionService(
            PostingRepository postings, PostingPublisher publisher, TransactionTemplate transaction, Clock clock) {
        this.postings = postings;
        this.publisher = publisher;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** Runs a connector and ingests what it returns. */
    public IngestionResult run(SourceConnector connector) {
        return ingest(connector.source(), connector.fetch());
    }

    /** Ingests the postings of one connector run and records the run in {@code source_run}. */
    public IngestionResult ingest(String source, List<RawPosting> rawPostings) {
        Instant startedAt = clock.instant();
        int newItems = 0;
        int hot = 0;
        List<String> errors = new ArrayList<>();

        for (RawPosting raw : rawPostings) {
            try {
                // One transaction per posting, so one bad posting cannot roll back the others.
                PostingQueue queue = transaction.execute(status -> storeAndPublish(raw));
                if (queue != null) {
                    newItems++;
                    if (queue == PostingQueue.HOT) {
                        hot++;
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Failed to ingest {}:{}", raw.source(), raw.sourceId(), e);
                errors.add(raw.sourceId() + ": " + e.getClass().getSimpleName());
            }
        }

        postings.recordRun(source, startedAt, rawPostings.size(), newItems, errors);
        return new IngestionResult(rawPostings.size(), newItems, hot, errors.size());
    }

    /** Returns the queue the posting went to, or null when it was a duplicate. */
    private PostingQueue storeAndPublish(RawPosting raw) {
        String applyTarget = PostingNormalizer.applyTarget(raw.applyTarget());
        String contentHash = PostingNormalizer.contentHash(raw.companyName(), raw.title(), raw.location());

        // Dedupe across sources: same apply URL, or same company + title + location.
        if (postings.existsFromAnotherSource(raw.source(), raw.sourceId(), applyTarget, contentHash)) {
            return null;
        }

        Instant firstSeenAt = clock.instant();
        // Empty when this source already delivered the posting (idempotent re-run).
        OptionalLong id = postings.insert(raw, applyTarget, contentHash, firstSeenAt);
        if (id.isEmpty()) {
            return null;
        }

        // A posting with no posted_at is fresh on this first sighting: its age counts from now.
        PostingQueue queue =
                PostingAge.isHot(raw.postedAt(), firstSeenAt, firstSeenAt) ? PostingQueue.HOT : PostingQueue.BACKLOG;
        publisher.publish(new PostingIngested(id.getAsLong(), firstSeenAt, raw.postedAt()), queue);
        return queue;
    }
}
