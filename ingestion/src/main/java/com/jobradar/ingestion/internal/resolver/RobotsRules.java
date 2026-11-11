package com.jobradar.ingestion.internal.resolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The part of robots.txt the resolver needs: may this user agent fetch this path?
 * Follows RFC 9309: the group naming our agent wins over {@code *}, the longest matching rule
 * decides, and Allow wins a tie.
 */
public final class RobotsRules {

    private record Rule(boolean allow, String path) {}

    private RobotsRules() {}

    public static boolean allows(String robotsTxt, String agentToken, String path) {
        List<Rule> own = new ArrayList<>();
        List<Rule> wildcard = new ArrayList<>();
        boolean ownGroup = false;
        boolean wildcardGroup = false;
        boolean readingAgents = false; // consecutive User-agent lines share one group

        for (String rawLine : robotsTxt.split("\\R")) {
            String line = rawLine.replaceFirst("#.*", "").strip();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (field.equals("user-agent")) {
                if (!readingAgents) {
                    ownGroup = false;
                    wildcardGroup = false;
                }
                readingAgents = true;
                ownGroup |= value.equalsIgnoreCase(agentToken);
                wildcardGroup |= value.equals("*");
            } else if (field.equals("allow") || field.equals("disallow")) {
                readingAgents = false;
                if (value.isEmpty()) {
                    continue; // "Disallow:" with no path allows everything
                }
                Rule rule = new Rule(field.equals("allow"), value);
                if (ownGroup) {
                    own.add(rule);
                }
                if (wildcardGroup) {
                    wildcard.add(rule);
                }
            } else {
                readingAgents = false;
            }
        }

        Rule best = null;
        for (Rule rule : own.isEmpty() ? wildcard : own) {
            if (matches(rule.path(), path)
                    && (best == null
                            || rule.path().length() > best.path().length()
                            || (rule.path().length() == best.path().length() && rule.allow()))) {
                best = rule;
            }
        }
        return best == null || best.allow();
    }

    /** Prefix match with the two robots.txt wildcards: {@code *} (any run) and a trailing {@code $} (end). */
    private static boolean matches(String rulePath, String path) {
        boolean anchored = rulePath.endsWith("$");
        String body = anchored ? rulePath.substring(0, rulePath.length() - 1) : rulePath;
        StringBuilder regex = new StringBuilder();
        for (String literal : body.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(literal));
        }
        regex.append(anchored ? "" : ".*");
        // DOTALL is not needed: URL paths hold no line breaks.
        return Pattern.matches(regex.toString(), path);
    }
}
