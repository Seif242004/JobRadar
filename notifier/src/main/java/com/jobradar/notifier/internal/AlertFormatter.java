package com.jobradar.notifier.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Plain-text alert messages. Plain text avoids escaping bugs with titles that contain markup. */
final class AlertFormatter {

    /** Deadlines are shown in the users' time zone; all current users are in Egypt. */
    private static final DateTimeFormatter DEADLINE =
            DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH).withZone(ZoneId.of("Africa/Cairo"));

    /** A Telegram message holds 4096 characters; a batch lists at most this many postings. */
    static final int MAX_BATCH_ITEMS = 10;

    private AlertFormatter() {}

    static String applyNow(AlertPosting posting) {
        return "🔥 Apply now\n" + headline(posting) + "\n" + nullToEmpty(posting.reasons());
    }

    /** Apply-now push with the tailored CV: fit, ATS keyword coverage and the presigned PDF link. */
    static String applyNowWithCv(AlertPosting posting, double atsScore, String cvLink) {
        return "🔥 Apply now\n" + headline(posting)
                + "\nFit " + Math.round(posting.fitScore()) + " · ATS keywords " + Math.round(atsScore) + "%"
                + "\n" + nullToEmpty(posting.reasons())
                + "\n\nTailored CV: " + cvLink;
    }

    static String deadlineSoon(AlertPosting posting, Instant now) {
        long hours = Math.max(1, Duration.between(now, posting.deadline()).toHours());
        String left = hours >= 48 ? (hours / 24) + " days" : hours + " hours";
        return "⏰ Deadline soon: closes " + DEADLINE.format(posting.deadline()) + " (" + left + " left)\n"
                + headline(posting);
    }

    /** One message listing the batch, best first, each with its apply link. */
    static String warmBatch(List<AlertPosting> postings) {
        StringBuilder text = new StringBuilder("New matches in the last 24 hours (" + postings.size() + ")\n");
        int index = 1;
        for (AlertPosting posting : postings.subList(0, Math.min(postings.size(), MAX_BATCH_ITEMS))) {
            text.append('\n').append(index++).append(". ").append(headline(posting));
            if (isUrl(posting.applyTarget())) {
                text.append('\n').append(posting.applyTarget());
            }
            text.append('\n');
        }
        if (postings.size() > MAX_BATCH_ITEMS) {
            text.append("\n…and ").append(postings.size() - MAX_BATCH_ITEMS).append(" more in the dashboard.");
        }
        return text.toString().strip();
    }

    /** Twice-daily push of the best matches that no other alert covered; fit is shown per line. */
    static String topPicks(List<AlertPosting> postings) {
        StringBuilder text = new StringBuilder("⭐ Top picks for you (" + postings.size() + ")\n");
        int index = 1;
        for (AlertPosting posting : postings) {
            text.append('\n').append(index++).append(". ").append(headline(posting));
            if (posting.fitScore() != null) {
                text.append(" · fit ").append(Math.round(posting.fitScore()));
            }
            if (isUrl(posting.applyTarget())) {
                text.append('\n').append(posting.applyTarget());
            }
            text.append('\n');
        }
        return text.toString().strip();
    }

    /** "Backend Intern · Instabug · Cairo, Egypt" with missing parts left out. */
    static String headline(AlertPosting posting) {
        StringBuilder line = new StringBuilder(posting.title());
        if (posting.company() != null) {
            line.append(" · ").append(posting.company());
        }
        if (posting.location() != null && !posting.location().isBlank()) {
            line.append(" · ").append(posting.location());
        }
        return line.toString();
    }

    /** Only http(s) targets can be opened by a button; an email target is shown by M3's apply flow. */
    static boolean isUrl(String target) {
        return target != null && (target.startsWith("https://") || target.startsWith("http://"));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
