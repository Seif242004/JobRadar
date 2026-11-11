package com.jobradar.tailoring.internal;

import java.util.List;

/** Everything tailoring reads for one user and posting. */
public record TailoringData(
        long userId,
        long postingId,
        String postingTitle,
        String postingCompany,
        String postingLocation,
        String postingDescription,
        CvStatic cvStatic,
        List<CvEntry> entries,
        List<ProfileBullet> bullets,
        List<CvSummary> summaries) {

    /** Bullets that may appear on a CV: everything except unconfirmed ones. */
    public List<ProfileBullet> selectableBullets() {
        return bullets.stream().filter(bullet -> !bullet.verify()).toList();
    }
}
