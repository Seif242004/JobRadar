package com.jobradar.ingestion.internal.alerts;

import com.jobradar.ingestion.RawPosting;
import com.jobradar.ingestion.internal.source.SourceText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the postings out of one alert email for one board.
 *
 * <p>Works from what every alert has in common rather than from one template: a job link, the
 * link text as the title, and the one or two short lines after it as company and location. It
 * reads the HTML part and the plain-text part and keeps whichever gave more for each job. An
 * item that cannot be understood is skipped and counted; nothing here throws.
 */
final class AlertExtractor {

    static final String SOURCE = "gmail";

    /** Link text and lines that are buttons or decoration, not job data. */
    private static final Pattern NOISE = Pattern.compile(
            "(?i)^(apply with .*|this company is .*|job search smarter.*|\\d+ (company |school )?(alumni|connections?).*|1 connection.*|view( job| jobs| details)?|see( all)?( jobs| job| details)?|apply( now)?|easy apply|quick apply|"
                    + "learn more|unsubscribe|manage.*|actively (recruiting|hiring)|be an early applicant|promoted|new|"
                    + "\\d+ (new )?(jobs?|applicants?).*|\\d+ (minute|hour|day|week)s? ago|posted .*|view job:?.*|"
                    + "https?://.*|[\u2022\u00b7|\\-\u2013\u2014]+)$");
    /** Footer and navigation text that can sit right after the last job of an email. */
    private static final Pattern FOOTER = Pattern.compile(
            "(?i)\\b(unsubscribe|view all|see all|more jobs|manage (your )?(job )?(alerts?|preferences|subscriptions?)|privacy|terms of|help center|download the app)\\b");
    private static final Pattern SEPARATOR = Pattern.compile("\\s+[\u00b7|\u2022\u2013\u2014-]\\s+");
    private static final int MAX_FIELD = 200;
    private static final int LOOK_AHEAD_LINES = 3;
    private static final char MARK = '\u0001';

    /** Result of one email: the postings and how many job links could not be turned into one. */
    record Result(List<RawPosting> postings, int skipped) {}

    private AlertExtractor() {}

    static Result extract(AlertEmail email, BoardParser board) {
        Map<String, Candidate> byId = new LinkedHashMap<>();
        try {
            fromHtml(email.html(), board, byId);
        } catch (RuntimeException e) {
            // Tolerate anything odd in the markup: the plain-text part may still work.
        }
        try {
            fromText(email.text(), board, byId);
        } catch (RuntimeException e) {
            // Same: keep what the HTML gave.
        }

        List<RawPosting> postings = new ArrayList<>();
        int skipped = 0;
        for (Map.Entry<String, Candidate> entry : byId.entrySet()) {
            Candidate candidate = entry.getValue();
            if (candidate.title == null) {
                skipped++;
                continue;
            }
            postings.add(new RawPosting(
                    SOURCE,
                    board.board() + ":" + entry.getKey(),
                    candidate.company,
                    candidate.title,
                    candidate.location,
                    candidate.location != null && candidate.location.toLowerCase(Locale.ROOT).contains("remote") ? "remote" : null,
                    null,
                    null,
                    // Applying happens on the board itself, by the user. Never automated.
                    "platform",
                    candidate.url,
                    null,
                    email.receivedAt()));
        }
        return new Result(postings, skipped);
    }

    /** What is known about one job while the email is read. */
    private static final class Candidate {
        final String url;
        String title;
        String company;
        String location;

        Candidate(String url) {
            this.url = url;
        }

        int richness() {
            return (title == null ? 0 : 1) + (company == null ? 0 : 1) + (location == null ? 0 : 1);
        }

        void offer(String title, String company, String location) {
            Candidate other = new Candidate(url);
            other.title = title;
            other.company = company;
            other.location = location;
            if (other.richness() > richness()) {
                this.title = title;
                this.company = company;
                this.location = location;
            }
        }
    }

    // ------------------------------------------------------------------ HTML

    private static void fromHtml(String html, BoardParser board, Map<String, Candidate> byId) {
        if (html.isBlank()) {
            return;
        }
        // Mark every job anchor, in order, so its text can be found again after flattening to lines.
        List<BoardParser.JobRef> marked = new ArrayList<>();
        StringBuilder marked_html = new StringBuilder();
        java.util.regex.Matcher anchor = Pattern.compile(
                        "<a\\b[^>]*?\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')[^>]*>(.*?)</a\\s*>",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(html);
        int last = 0;
        while (anchor.find()) {
            String href = anchor.group(1) != null ? anchor.group(1) : anchor.group(2);
            Optional<BoardParser.JobRef> job = jobFor(SourceText.unescape(href).strip(), board);
            marked_html.append(html, last, anchor.start());
            if (job.isPresent()) {
                marked.add(job.get());
                marked_html.append(MARK).append(marked.size() - 1).append(MARK).append(anchor.group(3)).append(MARK).append("<br>");
            } else {
                marked_html.append(anchor.group());
            }
            last = anchor.end();
        }
        marked_html.append(html, last, html.length());
        if (marked.isEmpty()) {
            return;
        }

        // Table cells are lines too, otherwise "Company" and "Location" cells would merge.
        String flat = SourceText.plain(marked_html.toString().replaceAll("(?i)</t[dh]\\s*>", "<br>"));
        if (flat == null) {
            return;
        }
        String[] lines = flat.split("\n");
        for (int i = 0; i < lines.length; i++) {
            int index = markerIndex(lines[i]);
            if (index < 0 || index >= marked.size()) {
                continue;
            }
            BoardParser.JobRef ref = marked.get(index);
            String linkText = clean(lines[i].substring(lines[i].indexOf(MARK, 1) + 1).replace(String.valueOf(MARK), ""));
            Candidate candidate = byId.computeIfAbsent(ref.id(), key -> new Candidate(ref.url()));

            Optional<BoardParser.Parts> split = linkText == null ? Optional.empty() : board.split(linkText);
            if (split.isPresent()) {
                BoardParser.Parts parts = split.get();
                candidate.offer(parts.title(), parts.company(), parts.location());
                continue;
            }
            if (linkText == null || isNoise(linkText)) {
                continue; // a "View job" button or a logo: the title link of this job is elsewhere
            }
            List<String> following = new ArrayList<>();
            for (int j = i + 1; j < lines.length && following.size() < LOOK_AHEAD_LINES; j++) {
                if (lines[j].indexOf(MARK) >= 0) {
                    break; // next job
                }
                String line = clean(lines[j]);
                if (line != null && !isNoise(line)) {
                    following.add(line);
                }
            }
            offerWithFollowing(candidate, linkText, following);
        }
    }

    /** "{MARK}index{MARK}text{MARK}" at the start of a line; -1 when the line has none. */
    private static int markerIndex(String line) {
        if (line.isEmpty() || line.charAt(0) != MARK) {
            // A marker can follow leading text on the same line (for example a bullet).
            int at = line.indexOf(MARK);
            if (at < 0) {
                return -1;
            }
            line = line.substring(at);
        }
        int end = line.indexOf(MARK, 1);
        if (end < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(line.substring(1, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------ plain text

    private static void fromText(String text, BoardParser board, Map<String, Candidate> byId) {
        if (text.isBlank()) {
            return;
        }
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            for (String url : AlertLinks.urls(lines[i])) {
                Optional<BoardParser.JobRef> job = jobFor(url, board);
                if (job.isEmpty()) {
                    continue;
                }
                BoardParser.JobRef ref = job.get();
                // The lines just above the link, nearest last: title, company, location.
                List<String> above = new ArrayList<>();
                for (int j = i - 1; j >= 0 && above.size() < LOOK_AHEAD_LINES; j--) {
                    String line = clean(lines[j]);
                    if (line == null) {
                        continue; // a blank line can sit between location and the "actively hiring" lines
                    }
                    if (line.matches("^[-=_*#\\s]{3,}$") || line.contains("://")) {
                        break; // separator, or the previous job's link: this job's block starts after it
                    }
                    if (isNoise(line)) {
                        continue; // "Apply with resume & profile", "This company is actively hiring"
                    }
                    above.add(0, line);
                }
                if (above.isEmpty()) {
                    byId.computeIfAbsent(ref.id(), key -> new Candidate(ref.url()));
                    continue;
                }
                Candidate candidate = byId.computeIfAbsent(ref.id(), key -> new Candidate(ref.url()));
                String title = above.get(0);
                Optional<BoardParser.Parts> split = board.split(title);
                if (split.isPresent()) {
                    candidate.offer(split.get().title(), split.get().company(), split.get().location());
                } else {
                    offerWithFollowing(candidate, title, above.subList(1, above.size()));
                }
            }
        }
    }

    // ---------------------------------------------------------------- shared

    private static Optional<BoardParser.JobRef> jobFor(String url, BoardParser board) {
        for (String candidate : AlertLinks.candidates(url)) {
            Optional<BoardParser.JobRef> job = board.job(candidate);
            if (job.isPresent()) {
                return job;
            }
        }
        return Optional.empty();
    }

    /** First following line is "Company" or "Company - Location"; the next, when present, is the location. */
    private static void offerWithFollowing(Candidate candidate, String title, List<String> following) {
        String company = null;
        String location = null;
        if (!following.isEmpty()) {
            String first = following.get(0).replaceAll("(?i)\\s+[·|•–—-]\\s+posted\\b.*$", "");
            String[] split = SEPARATOR.split(first, 2);
            if (split.length == 2) {
                company = clean(split[0]);
                location = clean(split[1]);
            } else {
                company = first;
                if (following.size() > 1) {
                    location = following.get(1);
                }
            }
        }
        if (location != null) {
            location = clean(location.replaceAll("(?i)\\s+[·|•–—-]\\s+posted\\b.*$", ""));
        }
        candidate.offer(title, company, location);
    }

    private static boolean isNoise(String line) {
        return NOISE.matcher(line).matches() || FOOTER.matcher(line).find();
    }

    /** Trimmed, single-spaced, null when blank or too long to be a title, company or location. */
    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replace(String.valueOf(MARK), "").replaceAll("\\s+", " ").strip();
        return cleaned.isEmpty() || cleaned.length() > MAX_FIELD ? null : cleaned;
    }
}
