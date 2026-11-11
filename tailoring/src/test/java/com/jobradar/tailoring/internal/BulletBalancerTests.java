package com.jobradar.tailoring.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BulletBalancerTests {

    private static ProfileBullet b(long id, String section, Long entry) {
        return new ProfileBullet(id, section, "text " + id, false, entry);
    }

    private final List<ProfileBullet> bullets = List.of(
            b(1, "experience", 10L), b(2, "experience", 10L), b(3, "experience", 10L), b(4, "experience", 10L),
            b(5, "experience", 11L), b(6, "experience", 11L),
            b(7, "project", 20L), b(8, "project", 20L), b(9, "project", 20L),
            b(10, "project", 21L),
            b(11, "skill", null), b(12, "skill", null));

    @Test
    void everyEntryGetsItsBestBulletBeforeAnyEntryGetsASecond() {
        // The model ranked all of entry 10 first, which would fill a page with one job.
        List<Long> ranked = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);

        List<Long> balanced = BulletBalancer.balance(ranked, bullets);

        assertThat(balanced.subList(0, 6)).containsExactly(1L, 5L, 7L, 10L, 11L, 12L);
        // Second bullets follow, then third ones, and nothing beyond the caps.
        assertThat(balanced.subList(6, balanced.size())).containsExactly(2L, 6L, 8L, 3L, 9L);
    }

    @Test
    void capsBulletsPerJobAndPerProject() {
        List<Long> balanced = BulletBalancer.balance(List.of(1L, 2L, 3L, 4L, 7L, 8L, 9L), bullets);

        assertThat(balanced).containsExactlyInAnyOrder(1L, 2L, 3L, 7L, 8L, 9L);
    }

    @Test
    void onlyReturnsBulletsThatWereSelected() {
        assertThat(BulletBalancer.balance(List.of(5L, 99L), bullets)).containsExactly(5L);
    }

    @Test
    void theRemainderOfTheProfileFollowsTheModelsPicksWithoutDuplicates() {
        List<Long> extended = BulletBalancer.withRemainder(List.of(5L, 1L), bullets);

        assertThat(extended.subList(0, 2)).containsExactly(5L, 1L);
        assertThat(extended).hasSize(12).doesNotHaveDuplicates();
        assertThat(extended.subList(2, extended.size())).containsExactly(2L, 3L, 4L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
    }
}
