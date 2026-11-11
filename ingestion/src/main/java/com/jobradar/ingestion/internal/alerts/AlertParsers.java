package com.jobradar.ingestion.internal.alerts;

import com.jobradar.ingestion.RawPosting;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Runs every board parser over an email. The board is recognised by the job links in the body,
 * not by the sender, so an alert forwarded from another address, or one whose sender is a mail
 * relay, is still understood. Wuzzuf, Bayt and LinkedIn are the supported boards.
 */
@Component
class AlertParsers {

    private final List<BoardParser> boards = List.of(new WuzzufParser(), new BaytParser(), new LinkedInParser());

    /** Result of one email. {@code skipped} counts job links that could not be turned into a posting. */
    record Parsed(List<RawPosting> postings, int skipped) {}

    Parsed parse(AlertEmail email) {
        List<RawPosting> postings = new ArrayList<>();
        int skipped = 0;
        for (BoardParser board : boards) {
            AlertExtractor.Result result = AlertExtractor.extract(email, board);
            postings.addAll(result.postings());
            skipped += result.skipped();
        }
        return new Parsed(postings, skipped);
    }
}
