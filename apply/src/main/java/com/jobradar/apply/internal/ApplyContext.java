package com.jobradar.apply.internal;

/** Everything needed to apply to one posting for one user with one CV. */
public record ApplyContext(
        String postingTitle,
        String company,
        String applyChannel,
        String applyTarget,
        String userEmail,
        String gmailTokenRef,
        String name,
        int dailyApplyCap,
        String pdfKey) {}
