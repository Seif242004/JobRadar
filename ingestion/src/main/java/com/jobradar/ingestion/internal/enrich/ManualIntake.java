package com.jobradar.ingestion.internal.enrich;

import com.jobradar.ingestion.PostingIngested;
import com.jobradar.ingestion.PostingPublisher;
import com.jobradar.ingestion.PostingQueue;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Queues postings the user added or pasted text into (flagged {@code intake_pending} by the api).
 * Reads the apply method from the text, then publishes the posting to the hot queue so it is scored
 * against the user's CVs within seconds.
 */
public class ManualIntake {

    private static final Logger log = LoggerFactory.getLogger(ManualIntake.class);

    private record Pending(long id, String description, String channel, String target, Instant firstSeenAt) {}

    private final JdbcTemplate jdbc;
    private final PostingPublisher publisher;
    private final Clock clock;

    public ManualIntake(JdbcTemplate jdbc, PostingPublisher publisher, Clock clock) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "PT15S")
    public void run() {
        try {
            for (Pending p : pending()) {
                handle(p);
            }
        } catch (RuntimeException e) {
            log.warn("Manual intake failed", e);
        }
    }

    private List<Pending> pending() {
        return jdbc.query(
                "SELECT id, description, apply_channel, apply_target, first_seen_at FROM posting WHERE intake_pending ORDER BY id LIMIT 20",
                (rs, n) -> new Pending(
                        rs.getLong("id"), rs.getString("description"), rs.getString("apply_channel"),
                        rs.getString("apply_target"), rs.getTimestamp("first_seen_at").toInstant()));
    }

    void handle(Pending p) {
        ApplyMethodDetector.detect(p.description()).ifPresent(m -> {
            if (p.channel() == null || "platform".equals(p.channel()) || "ats_form".equals(p.channel())) {
                jdbc.update(
                        "UPDATE posting SET source_url = coalesce(source_url, apply_target), apply_channel = ?, apply_target = ? WHERE id = ?",
                        m.channel(), m.target(), p.id());
            }
        });
        // Manual postings are always scored promptly: the user is looking at them.
        publisher.publish(new PostingIngested(p.id(), p.firstSeenAt(), null), PostingQueue.HOT);
        jdbc.update("UPDATE posting SET intake_pending = false WHERE id = ?", p.id());
        log.info("Queued manual posting {}", p.id());
    }
}
