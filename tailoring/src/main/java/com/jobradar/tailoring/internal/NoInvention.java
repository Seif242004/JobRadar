package com.jobradar.tailoring.internal;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guard for free text that is not a stored bullet, such as a summary variant: every number and
 * every proper-looking term (capitalised in the middle of a sentence, or containing a digit,
 * {@code +}, {@code #}, {@code &} or {@code .}) must already occur in the user's CV text.
 * It cannot prove a sentence true, but it blocks a new employer, skill or figure.
 */
public final class NoInvention {

    private static final Pattern TERM = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}+#&.]*");

    private NoInvention() {}

    /** @return the terms of {@code text} that are not in {@code corpus}; empty when the text is clean */
    public static Set<String> unknownTerms(String text, String corpus) {
        String haystack = corpus.toLowerCase(Locale.ROOT);
        Set<String> unknown = new LinkedHashSet<>();
        Matcher matcher = TERM.matcher(text);
        boolean sentenceStart = true;
        int last = 0;
        while (matcher.find()) {
            // A word is at a sentence start when the text between it and the previous word has ". ".
            String gap = text.substring(last, matcher.start());
            if (gap.contains(". ") || gap.contains(": ")) {
                sentenceStart = true;
            }
            String raw = matcher.group();
            String term = raw.replaceAll("[.]+$", "");
            last = matcher.end();
            boolean special = term.matches(".*[\\d+#&].*") || (term.contains(".") && term.length() > 2);
            boolean capitalised = Character.isUpperCase(term.charAt(0)) && !sentenceStart;
            // A token that ends in a full stop closes its sentence ("things. Ships"), so the next word starts one.
            boolean sentenceEnds = raw.endsWith(".") && raw.length() > 1;
            sentenceStart = false;
            if ((special || capitalised) && !haystack.contains(term.toLowerCase(Locale.ROOT))) {
                unknown.add(term);
            }
            sentenceStart = sentenceEnds;
        }
        return unknown;
    }
}
