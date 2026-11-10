package com.jobradar.ingestion;

import java.time.Instant;

/**
 * One posting as a source connector read it, before normalisation and dedupe.
 *
 * @param source        connector name, for example {@code greenhouse}; part of the idempotency key
 * @param sourceId      the posting's ID inside that source; part of the idempotency key
 * @param companyName   employer name as the source spells it; may be null
 * @param title         job title
 * @param location      free-text location; may be null
 * @param remotePolicy  free text such as {@code remote}, {@code hybrid}, {@code onsite}; may be null
 * @param language      ISO 639-1 code of the posting text ({@code en}, {@code ar}); may be null
 * @param description   full posting text; may be null
 * @param applyChannel  how to apply: {@code email}, {@code google_form}, {@code ats_form}, {@code platform}
 * @param applyTarget   URL or email address to apply to; may be null
 * @param deadline      closing date, when the posting states one; otherwise null
 * @param postedAt      publication time, only when the source gives it; otherwise null
 */
public record RawPosting(
        String source,
        String sourceId,
        String companyName,
        String title,
        String location,
        String remotePolicy,
        String language,
        String description,
        String applyChannel,
        String applyTarget,
        Instant deadline,
        Instant postedAt) {}
