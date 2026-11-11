package com.jobradar.tailoring.internal;

import com.jobradar.cvscore.AtsScorer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses the summary variant for a posting by keyword overlap. Deterministic and free: no model
 * call, and the text of a variant is never changed.
 *
 * <p>A focus word found in the title counts {@value #TITLE_WEIGHT}, one found in the description
 * counts once per posting. With no overlap at all the first confirmed variant (lowest sort order)
 * is the default.
 */
public final class SummaryPicker {

    static final int TITLE_WEIGHT = 3;

    private SummaryPicker() {}

    public static Optional<CvSummary> pick(List<CvSummary> variants, String title, String description) {
        List<CvSummary> usable = variants.stream().filter(summary -> !summary.verify()).toList();
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        Set<String> titleTokens = Set.copyOf(AtsScorer.tokens(title));
        Set<String> descriptionTokens = Set.copyOf(AtsScorer.tokens(description));
        String lowerTitle = lower(title);
        String lowerDescription = lower(description);
        return usable.stream()
                .max(Comparator.comparingInt((CvSummary s) -> score(s, titleTokens, descriptionTokens, lowerTitle, lowerDescription))
                        // Ties go to the variant listed first.
                        .thenComparing(Comparator.comparingInt(CvSummary::sortOrder).reversed()));
    }

    private static int score(CvSummary summary, Set<String> titleTokens, Set<String> descTokens, String title, String desc) {
        int score = 0;
        for (String word : summary.focus()) {
            String w = word.toLowerCase(Locale.ROOT).strip();
            // Single words match as tokens (so "ai" never matches "maintain"); phrases match as text.
            boolean phrase = w.contains(" ") || w.contains("-");
            if (phrase ? title.contains(w) : titleTokens.contains(w)) {
                score += TITLE_WEIGHT;
            }
            if (phrase ? desc.contains(w) : descTokens.contains(w)) {
                score += 1;
            }
        }
        return score;
    }

    private static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
