package com.jobradar.ingestion.internal.source;

import com.jobradar.ingestion.RawPosting;
import com.jobradar.ingestion.SourceConnector;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base of the watchlist connectors: one public board feed per company, one request per board and
 * run. A board that fails (renamed token, outage) is logged and skipped so the others still run.
 */
abstract class BoardConnector implements SourceConnector {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final BoardDirectory boards;

    BoardConnector(BoardDirectory boards) {
        this.boards = boards;
    }

    /** Reads every posting of one board. */
    abstract List<RawPosting> fetchBoard(Board board);

    @Override
    public final List<RawPosting> fetch() {
        List<RawPosting> postings = new ArrayList<>();
        for (Board board : boards.boards(source())) {
            try {
                postings.addAll(fetchBoard(board));
            } catch (SourceRateLimitedException e) {
                // The whole host is limiting us: stop the run and let the scheduler back off.
                throw e;
            } catch (RuntimeException e) {
                log.warn("Skipping {} board {} ({}): {}", source(), board.token(), board.companyName(), e.getMessage());
            }
        }
        return postings;
    }
}
