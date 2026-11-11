package com.jobradar.tailoring.internal;

/**
 * One bullet of the master profile.
 *
 * @param verify  true when the user has not confirmed the wording; never placed on a CV
 * @param entryId the job, project or skill row it belongs to; may be null
 */
public record ProfileBullet(long id, String section, String text, boolean verify, Long entryId) {}
