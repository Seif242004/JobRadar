package com.jobradar.ingestion.internal.alerts;

import java.util.Optional;

/**
 * What differs between job boards: how to recognise one of their job links, and whether their
 * link text carries more than the title. Everything else is shared in {@link AlertExtractor}.
 */
interface BoardParser {

    /** A recognised job link. {@code id} is the board's job ID, {@code url} its canonical public URL. */
    record JobRef(String id, String url) {}

    /** Title, company and location read from one line of link text; company and location may be null. */
    record Parts(String title, String company, String location) {}

    /** Stable board name, used in {@code posting.source_id}. */
    String board();

    /** The job a URL points at, or empty when it is not a job link of this board. */
    Optional<JobRef> job(String url);

    /** Splits link text that carries title, company and location together (for example "A - B - C"). */
    default Optional<Parts> split(String linkText) {
        return Optional.empty();
    }
}
