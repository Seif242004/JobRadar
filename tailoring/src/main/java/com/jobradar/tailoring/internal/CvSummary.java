package com.jobradar.tailoring.internal;

import java.util.List;

/**
 * One summary-line variant of the master profile.
 *
 * @param focus  lower-case words; a posting that contains them selects this variant
 * @param verify true until the user confirms the wording; unconfirmed variants are never used
 */
public record CvSummary(long id, String label, List<String> focus, String text, boolean verify, int sortOrder) {}
