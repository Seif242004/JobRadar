package com.jobradar.matching.internal;

import java.time.Instant;

/** The posting columns matching needs. */
public record PostingView(
        long id,
        String title,
        String location,
        String remotePolicy,
        String description,
        Instant deadline,
        Instant postedAt,
        Instant firstSeenAt) {}
