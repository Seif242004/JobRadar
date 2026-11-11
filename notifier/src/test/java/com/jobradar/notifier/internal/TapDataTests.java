package com.jobradar.notifier.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TapDataTests {

    @Test
    void everyCodeRoundTrips() {
        assertThat(TapData.parse(TapData.approve(5, 9))).contains(new TapData.Approve(5, 9));
        assertThat(TapData.parse(TapData.markApplied(5, 9))).contains(new TapData.MarkApplied(5, 9));
        assertThat(TapData.parse(TapData.skip(5))).contains(new TapData.Skip(5));
        assertThat(TapData.parse(TapData.later(5))).contains(new TapData.Later(5));
        assertThat(TapData.parse(TapData.replied(7))).contains(new TapData.Replied(7));
        assertThat(TapData.parse(TapData.rejected(7))).contains(new TapData.Rejected(7));
    }

    @Test
    void anythingElseIsIgnored() {
        assertThat(TapData.parse(null)).isEmpty();
        assertThat(TapData.parse("")).isEmpty();
        assertThat(TapData.parse("ap:5")).isEmpty();
        assertThat(TapData.parse("ap:x:9")).isEmpty();
        assertThat(TapData.parse("zz:5")).isEmpty();
        assertThat(TapData.parse("sk:5:6")).isEmpty();
    }

    @Test
    void callbackDataFitsTelegramsLimit() {
        assertThat(TapData.approve(Long.MAX_VALUE, Long.MAX_VALUE).getBytes()).hasSizeLessThanOrEqualTo(64);
    }
}
