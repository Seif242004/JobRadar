package com.jobradar.dashboard;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the user add a posting by hand (a link, the pasted text, or both) and paste the full text of
 * a posting that only has an alert snippet. Rows are flagged {@code intake_pending}; the ingestion
 * worker reads the apply method and queues them for scoring against the user's CVs.
 */
@RestController
@Profile({"local-dashboard", "dashboard"})
@RequestMapping("/api/dashboard/postings")
class ManualPostingController {

    static final int MIN_TEXT = 40;
    static final int MAX_TEXT = 20_000;

    record NewPosting(String url, String title, String company, String location, String text) {}

    record TextBody(String text) {}

    private final JdbcTemplate jdbc;

    ManualPostingController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds a posting from a link and/or pasted text. */
    @PostMapping
    ResponseEntity<Map<String, Object>> add(@RequestBody NewPosting body) {
        String url = body.url() == null ? "" : body.url().strip();
        String text = clean(body.text());
        if (!url.isEmpty() && !url.matches("(?i)^https?://\\S+$")) {
            return bad("The link must start with http:// or https://");
        }
        if (url.isEmpty() && text.length() < MIN_TEXT) {
            return bad("Paste a link, or at least " + MIN_TEXT + " characters of the posting text.");
        }
        String title = body.title() == null || body.title().isBlank() ? firstLine(text, url) : body.title().strip();
        String company = body.company() == null ? "" : body.company().strip();
        Long companyId = company.isEmpty()
                ? null
                : jdbc.queryForObject(
                        "INSERT INTO company (name) VALUES (?) ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name RETURNING id",
                        Long.class, company);
        String sourceId = sha(url.isEmpty() ? text : url);
        String hash = sha(company + "|" + title);
        List<Long> ids = jdbc.queryForList(
                """
                INSERT INTO posting (company_id, source, source_id, title, location, description, apply_channel, apply_target,
                                     first_seen_at, content_hash, intake_pending)
                VALUES (?, 'manual', ?, ?, ?, ?, ?, ?, now(), ?, true)
                ON CONFLICT (source, source_id) DO NOTHING
                RETURNING id
                """,
                Long.class, companyId, sourceId, title,
                body.location() == null || body.location().isBlank() ? null : body.location().strip(),
                text.isEmpty() ? null : text,
                url.isEmpty() ? null : "platform", url.isEmpty() ? null : url, hash);
        if (ids.isEmpty()) {
            Long existing = jdbc.queryForObject(
                    "SELECT id FROM posting WHERE source = 'manual' AND source_id = ?", Long.class, sourceId);
            return ResponseEntity.ok(Map.of("id", existing, "duplicate", true));
        }
        return ResponseEntity.ok(Map.of("id", ids.get(0), "duplicate", false));
    }

    /** Replaces a posting's text with the full text the user pasted, so it is scored again. */
    @PostMapping("/{id}/description")
    ResponseEntity<Map<String, Object>> description(@PathVariable long id, @RequestBody TextBody body) {
        String text = clean(body.text());
        if (text.length() < MIN_TEXT) {
            return bad("Paste at least " + MIN_TEXT + " characters of the posting text.");
        }
        int updated = jdbc.update(
                "UPDATE posting SET description = ?, embedding = NULL, enrich_status = 'manual', enriched_at = now(),"
                        + " intake_pending = true WHERE id = ?",
                text, id);
        return updated == 0 ? ResponseEntity.notFound().build() : ResponseEntity.ok(Map.of("id", id));
    }

    private static ResponseEntity<Map<String, Object>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        return stripped.length() > MAX_TEXT ? stripped.substring(0, MAX_TEXT) : stripped;
    }

    /** First non-empty line of the text, capped; falls back to the link's host. */
    private static String firstLine(String text, String url) {
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                String t = line.strip();
                return t.length() > 120 ? t.substring(0, 120) : t;
            }
        }
        return url.isEmpty() ? "Untitled posting" : "Posting from " + java.net.URI.create(url).getHost();
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
