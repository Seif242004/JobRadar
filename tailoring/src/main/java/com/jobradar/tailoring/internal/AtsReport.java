package com.jobradar.tailoring.internal;

import java.util.List;

/**
 * Result of the ATS check on one rendered CV.
 *
 * @param score     share of the posting's keywords found in the CV text, 0 to 100
 * @param matched   posting keywords that the CV contains
 * @param missing   posting keywords that it does not contain
 * @param checks    pass or fail checks on the PDF itself
 * @param fixes     what the user can do, in plain words; never suggests claiming something untrue
 */
public record AtsReport(double score, List<String> matched, List<String> missing, List<Check> checks, List<String> fixes) {

    public record Check(String name, boolean ok, String detail) {}
}
