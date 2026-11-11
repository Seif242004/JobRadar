package com.jobradar.matching;

import java.util.Locale;

/** Freshness bands of docs/RESEARCH.md, "Freshness first". Stored lower-case in {@code match.band}. */
public enum Band {
    /** Under 2 hours old (and fit 80+ once fit scores exist): instant "Apply now" push. */
    HOT,
    /** 2 hours to 7 days old: batched into an alert every 3 hours. */
    WARM,
    /** Over 7 days old: never pushed. */
    BACKLOG,
    /** Has a closing date: exempt from decay, pushed once 3 days before the deadline. */
    DEADLINE;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
