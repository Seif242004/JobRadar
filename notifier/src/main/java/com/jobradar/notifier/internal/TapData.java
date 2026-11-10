package com.jobradar.notifier.internal;

import java.util.Optional;

/**
 * What a button sends back, as {@code <code>:<id>[:<id>]}. Telegram limits callback data to 64
 * bytes; all ids are numbers, so this always fits.
 */
sealed interface TapData {

    /** Approve and send the application email. */
    record Approve(long postingId, long cvVersionId) implements TapData {}

    /** "I applied": the user applied by other means. */
    record MarkApplied(long postingId, long cvVersionId) implements TapData {}

    record Skip(long postingId) implements TapData {}

    record Later(long postingId) implements TapData {}

    /** Follow-up buttons: the employer replied, or rejected. */
    record Replied(long applicationId) implements TapData {}

    record Rejected(long applicationId) implements TapData {}

    static String approve(long postingId, long cvVersionId) {
        return "ap:" + postingId + ":" + cvVersionId;
    }

    static String markApplied(long postingId, long cvVersionId) {
        return "ma:" + postingId + ":" + cvVersionId;
    }

    static String skip(long postingId) {
        return "sk:" + postingId;
    }

    static String later(long postingId) {
        return "lt:" + postingId;
    }

    static String replied(long applicationId) {
        return "fr:" + applicationId;
    }

    static String rejected(long applicationId) {
        return "fj:" + applicationId;
    }

    /** Empty for anything that is not one of the codes above, for example a stale button. */
    static Optional<TapData> parse(String data) {
        if (data == null) {
            return Optional.empty();
        }
        String[] parts = data.split(":");
        try {
            return switch (parts[0] + "/" + parts.length) {
                case "ap/3" -> Optional.of(new Approve(Long.parseLong(parts[1]), Long.parseLong(parts[2])));
                case "ma/3" -> Optional.of(new MarkApplied(Long.parseLong(parts[1]), Long.parseLong(parts[2])));
                case "sk/2" -> Optional.of(new Skip(Long.parseLong(parts[1])));
                case "lt/2" -> Optional.of(new Later(Long.parseLong(parts[1])));
                case "fr/2" -> Optional.of(new Replied(Long.parseLong(parts[1])));
                case "fj/2" -> Optional.of(new Rejected(Long.parseLong(parts[1])));
                default -> Optional.empty();
            };
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
