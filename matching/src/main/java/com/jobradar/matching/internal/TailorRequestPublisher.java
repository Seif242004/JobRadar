package com.jobradar.matching.internal;

/** Asks the tailoring-worker for a CV. Public so tests in the api can replace it. */
public interface TailorRequestPublisher {

    /** @param hot true for the hot tailoring queue, false for the backlog queue */
    void publish(long userId, long postingId, boolean hot);
}
