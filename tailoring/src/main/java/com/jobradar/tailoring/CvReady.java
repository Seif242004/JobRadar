package com.jobradar.tailoring;

/**
 * Queue message published when a tailored CV has been rendered and stored.
 *
 * @param atsScore keyword coverage of the posting by the CV, 0 to 100
 */
public record CvReady(long userId, long postingId, long cvVersionId, double atsScore) {}
