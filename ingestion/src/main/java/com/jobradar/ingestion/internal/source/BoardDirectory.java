package com.jobradar.ingestion.internal.source;

import java.util.List;

/** The boards to poll for one ATS: companies whose {@code ats_type} the careers resolver set. */
@FunctionalInterface
public interface BoardDirectory {

    List<Board> boards(String atsType);
}
