package com.jobradar.matching;

/**
 * Published inside the api after a posting was matched against one user.
 *
 * @param eligible false when a hard filter rejected the posting; such matches are never pushed
 * @param fitScore 0 to 100, or null when no fit could be computed (SPEC.md D8)
 */
public record MatchScored(long userId, long postingId, boolean eligible, Band band, Double fitScore) {}
