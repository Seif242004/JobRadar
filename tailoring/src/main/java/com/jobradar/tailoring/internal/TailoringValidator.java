package com.jobradar.tailoring.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The no-invention validator (guardrail 4). A selection is valid only when every ID is one of
 * the user's confirmed bullets and no ID repeats. Because a CV is rendered from these IDs alone,
 * a valid selection cannot contain a skill, number or employer the user did not write.
 */
public final class TailoringValidator {

    /** @param problem null when valid, otherwise what was wrong */
    public record Result(boolean valid, String problem) {}

    private TailoringValidator() {}

    public static Result validate(List<Long> selectedIds, List<ProfileBullet> selectable) {
        if (selectedIds == null || selectedIds.isEmpty()) {
            return new Result(false, "No bullets selected");
        }
        Set<Long> allowed = selectable.stream()
                .filter(bullet -> !bullet.verify())
                .map(ProfileBullet::id)
                .collect(Collectors.toSet());
        Set<Long> seen = new HashSet<>();
        for (Long id : selectedIds) {
            if (id == null || !allowed.contains(id)) {
                return new Result(false, "Bullet " + id + " is not a confirmed bullet of this profile");
            }
            if (!seen.add(id)) {
                return new Result(false, "Bullet " + id + " was selected twice");
            }
        }
        return new Result(true, null);
    }
}
