package com.jobradar.tailoring.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a relevance-ordered list of bullet IDs into one that gives a CV its usual shape: several
 * jobs and projects, each with a few bullets, instead of one entry that fills the page.
 *
 * <p>Entries are taken in the order of their best bullet. Each pass hands every entry its next
 * most relevant bullet, so the first bullet of every relevant entry comes before the second
 * bullet of any entry. The page-fit loop then drops from the end, which are the second and third
 * bullets of the least relevant entries. Only IDs that were already selected are returned, and
 * no bullet is changed.
 */
public final class BulletBalancer {

    /** Most bullets one job gets, and one project gets. Skill rows are one bullet each. */
    static final int MAX_PER_EXPERIENCE = 3;
    static final int MAX_PER_PROJECT = 3;

    private BulletBalancer() {}

    /**
     * Appends every selectable bullet the model did not pick, in profile order, after the model's
     * picks. The model's choice stays first, but the page-fit step now has enough bullets to fill
     * the page and to give every job and project at least one bullet, as a hand-written CV does.
     * Only the user's own confirmed bullets are added; nothing is created.
     */
    public static List<Long> withRemainder(List<Long> rankedIds, List<ProfileBullet> selectable) {
        List<Long> result = new ArrayList<>(rankedIds);
        java.util.Set<Long> seen = new java.util.HashSet<>(rankedIds);
        for (ProfileBullet bullet : selectable) {
            if (seen.add(bullet.id())) {
                result.add(bullet.id());
            }
        }
        return result;
    }

    public static List<Long> balance(List<Long> rankedIds, List<ProfileBullet> bullets) {
        Map<Long, ProfileBullet> byId = new LinkedHashMap<>();
        bullets.forEach(bullet -> byId.put(bullet.id(), bullet));

        // Group by entry (a skill row is its own group), keeping the order of first appearance.
        Map<String, List<Long>> groups = new LinkedHashMap<>();
        for (Long id : rankedIds) {
            ProfileBullet bullet = byId.get(id);
            if (bullet == null) {
                continue;
            }
            String key = bullet.entryId() == null || bullet.section().equals("skill")
                    ? "row:" + id
                    : bullet.section() + ":" + bullet.entryId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(id);
        }

        List<Long> result = new ArrayList<>();
        for (int pass = 0; ; pass++) {
            boolean added = false;
            for (Map.Entry<String, List<Long>> group : groups.entrySet()) {
                int cap = group.getKey().startsWith("experience:") ? MAX_PER_EXPERIENCE
                        : group.getKey().startsWith("project:") ? MAX_PER_PROJECT : 1;
                if (pass < cap && pass < group.getValue().size()) {
                    result.add(group.getValue().get(pass));
                    added = true;
                }
            }
            if (!added) {
                return result;
            }
        }
    }
}
