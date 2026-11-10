package com.jobradar.tailoring;

/** Queue message asking for a tailored CV for one user and posting (consumer-side shape). */
public record TailorRequested(long userId, long postingId) {}
