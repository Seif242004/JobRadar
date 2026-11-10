package com.jobradar.notifier.internal;

import java.time.Instant;

/** What an alert shows about one posting. */
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
        String reasons) {}
