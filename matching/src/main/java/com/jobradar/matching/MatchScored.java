package com.jobradar.matching;

/**
 * Published inside the api after a posting was matched against one user.
 *
 * @param eligible false when a hard filter rejected the posting; such matches are never pushed
 */
public record MatchScored(long userId, long postingId, boolean eligible, Band band) {}
