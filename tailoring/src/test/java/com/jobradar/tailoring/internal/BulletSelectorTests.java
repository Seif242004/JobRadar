package com.jobradar.tailoring.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BulletSelectorTests {

    private final BulletSelector selector = new BulletSelector(null, JsonMapper.builder().build());

    @Test
    void readsPlainJson() {
        assertThat(selector.parse("{\"bullet_ids\": [3, 1, 2]}")).contains(List.of(3L, 1L, 2L));
    }

    @Test
    void toleratesCodeFencesAndProse() {
        String reply = "Here you go:\n```json\n{\"bullet_ids\":[5,4]}\n```";
        assertThat(selector.parse(reply)).contains(List.of(5L, 4L));
    }

    @Test
    void rejectsRepliesItCannotTrust() {
        assertThat(selector.parse("no json here")).isEmpty();
        assertThat(selector.parse("{\"ids\": [1]}")).isEmpty();
        assertThat(selector.parse("{\"bullet_ids\": \"1,2\"}")).isEmpty();
        // A string or a fraction is not an ID; the whole reply is dropped rather than repaired.
        assertThat(selector.parse("{\"bullet_ids\": [1, \"2\"]}")).isEmpty();
        assertThat(selector.parse("{\"bullet_ids\": [1, 2.5]}")).isEmpty();
        assertThat(selector.parse(null)).isEmpty();
    }
}
