package com.jobradar.tracker;

import java.util.Locale;

/** Values of {@code application.status} that the user can set. {@code sending} is internal to apply. */
public enum ApplicationStatus {
    APPLIED,
    REPLIED,
    INTERVIEW,
    OFFER,
    REJECTED;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
