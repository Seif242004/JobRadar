package com.jobradar.matching.internal;

/**
 * Result of the hard filters.
 *
 * @param reason one line: why the posting was rejected, or what made it pass
 */
public record Eligibility(boolean eligible, String reason) {

    static Eligibility rejected(String reason) {
        return new Eligibility(false, reason);
    }

    static Eligibility accepted(String reason) {
        return new Eligibility(true, reason);
    }
}
