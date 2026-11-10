package com.jobradar.tracker;

import java.time.Instant;

/** One application as the tracker shows it. */
public record TrackedApplication(
        long id,
        long postingId,
        String title,
        String company,
        String channel,
        ApplicationStatus status,
        Instant sentAt,
        Instant followUpAt) {}
