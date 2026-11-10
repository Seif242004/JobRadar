package com.jobradar.notifier.internal;

import java.util.Locale;

/** Values of {@code notification.type} sent so far; later milestones add follow-up and digest. */
public enum NotificationType {
    APPLY_NOW,
    WARM_BATCH,
    DEADLINE_SOON;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
