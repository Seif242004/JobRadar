package com.jobradar.ingestion.internal.resolver;

/**
 * What the careers resolver stores for a company.
 *
 * @param atsType    {@code company.ats_type}: greenhouse, lever, workable, smartrecruiters,
 *                   workday, or {@code email-alerts} for a custom careers site
 * @param boardToken {@code company.board_token}; null for {@code email-alerts}
 */
public record AtsBoard(String atsType, String boardToken) {

    /** A custom careers site: the company is followed through its job-alert emails instead. */
    public static final AtsBoard EMAIL_ALERTS = new AtsBoard("email-alerts", null);
}
