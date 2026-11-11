package com.jobradar.matching;

/**
 * What other modules may ask of matching. The implementation lives in {@code internal}, so the
 * dashboard depends on this interface and not on matching's classes.
 */
public interface MatchingApi {

    /** Re-evaluates every posting for the user after their search preferences changed. */
    int rematch(long userId);

    /** Asks tailoring to build (or rebuild) the CV for one posting. */
    void requestTailor(long userId, long postingId);
}
