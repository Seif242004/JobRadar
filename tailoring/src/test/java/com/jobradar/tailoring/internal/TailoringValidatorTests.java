package com.jobradar.tailoring.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TailoringValidatorTests {

    private static final List<ProfileBullet> BULLETS = List.of(
            new ProfileBullet(1, "experience", "Built APIs", false, 10L),
            new ProfileBullet(2, "project", "Wrote a renderer", false, 11L),
            new ProfileBullet(3, "project", "Unconfirmed claim", true, 11L));

    @Test
    void acceptsConfirmedBulletsInAnyOrder() {
        assertThat(TailoringValidator.validate(List.of(2L, 1L), BULLETS).valid()).isTrue();
    }

    @Test
    void rejectsAnIdThatDoesNotExist() {
        TailoringValidator.Result result = TailoringValidator.validate(List.of(1L, 99L), BULLETS);
        assertThat(result.valid()).isFalse();
        assertThat(result.problem()).contains("99");
    }

    @Test
    void rejectsAnUnconfirmedBullet() {
        // Bullet 3 exists in the profile but is tagged verify, so it must never reach a CV.
        assertThat(TailoringValidator.validate(List.of(1L, 3L), BULLETS).valid()).isFalse();
    }

    @Test
    void rejectsDuplicatesEmptyAndNullSelections() {
        assertThat(TailoringValidator.validate(List.of(1L, 1L), BULLETS).valid()).isFalse();
        assertThat(TailoringValidator.validate(List.of(), BULLETS).valid()).isFalse();
        assertThat(TailoringValidator.validate(null, BULLETS).valid()).isFalse();
        assertThat(TailoringValidator.validate(Arrays.asList(1L, null), BULLETS).valid()).isFalse();
    }
}
