package com.jobradar.ingestion.internal.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RobotsRulesTests {

    @Test
    void disallowIsAPrefixMatchForTheWildcardGroup() {
        String robots = "User-agent: *\nDisallow: /private\n";

        assertThat(RobotsRules.allows(robots, "JobRadar", "/careers")).isTrue();
        assertThat(RobotsRules.allows(robots, "JobRadar", "/private/careers")).isFalse();
    }

    @Test
    void theLongestRuleDecidesAndAllowWinsATie() {
        String robots = "User-agent: *\nDisallow: /\nAllow: /careers\n";

        assertThat(RobotsRules.allows(robots, "JobRadar", "/careers/")).isTrue();
        assertThat(RobotsRules.allows(robots, "JobRadar", "/about")).isFalse();
    }

    @Test
    void aGroupNamingOurAgentReplacesTheWildcardGroup() {
        String robots = "User-agent: *\nAllow: /\n\nUser-agent: jobradar\nDisallow: /careers\n";

        assertThat(RobotsRules.allows(robots, "JobRadar", "/careers")).isFalse();
    }

    @Test
    void wildcardsAndEndAnchorsAreHonoured() {
        String robots = "User-agent: *\nDisallow: /*?sort=\nDisallow: /jobs/$\n";

        assertThat(RobotsRules.allows(robots, "JobRadar", "/careers?sort=new")).isFalse();
        assertThat(RobotsRules.allows(robots, "JobRadar", "/jobs/")).isFalse();
        assertThat(RobotsRules.allows(robots, "JobRadar", "/jobs/backend")).isTrue();
    }

    @Test
    void anEmptyDisallowAndOtherAgentsGroupsRestrictNothing() {
        String robots = "User-agent: *\nDisallow:\n\nUser-agent: OtherBot\nDisallow: /\n";

        assertThat(RobotsRules.allows(robots, "JobRadar", "/careers")).isTrue();
    }
}
