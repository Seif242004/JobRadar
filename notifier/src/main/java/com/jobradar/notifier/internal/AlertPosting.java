package com.jobradar.notifier.internal;

import java.time.Instant;

/**
 * What an alert shows about one posting.
 *
 * @param fitScore   0 to 100, or null when no fit score exists
 * @param freshSince the earlier of posted_at and first_seen_at; the posting's age is measured from it
 */
public record AlertPosting(
        long userId,
        long chatId,
        long postingId,
        String title,
        String company,
        String location,
        String applyChannel,
        String applyTarget,
        Instant deadline,
        String reasons,
        Double fitScore,
        Instant freshSince,
        double pushMinFit,
        double digestMinFit) {}
