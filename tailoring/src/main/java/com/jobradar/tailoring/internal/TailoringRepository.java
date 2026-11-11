package com.jobradar.tailoring.internal;

import com.jobradar.tailoring.CvReady;
import java.sql.Array;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class TailoringRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    TailoringRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Empty when the posting is gone, or the user has no profile or no CV data. */
    public Optional<TailoringData> load(long userId, long postingId) {
        List<String> cvStatic = jdbc.queryForList(
                "SELECT cv_static::text FROM profile WHERE user_id = ? AND cv_static IS NOT NULL", String.class, userId);
        if (cvStatic.isEmpty()) {
            return Optional.empty();
        }
        List<CvEntry> entries = jdbc.query(
                "SELECT id, section, title, dates, context, sort_order FROM cv_entry WHERE user_id = ? ORDER BY sort_order",
                (rs, n) -> new CvEntry(
                        rs.getLong("id"), rs.getString("section"), rs.getString("title"),
                        rs.getString("dates"), rs.getString("context"), rs.getInt("sort_order")),
                userId);
        List<ProfileBullet> bullets = jdbc.query(
                "SELECT id, section, text, 'verify' = ANY (tags) AS verify, entry_id FROM bullet WHERE user_id = ? ORDER BY id",
                (rs, n) -> {
                    long entryId = rs.getLong("entry_id");
                    return new ProfileBullet(
                            rs.getLong("id"), rs.getString("section"), rs.getString("text"),
                            rs.getBoolean("verify"), rs.wasNull() ? null : entryId);
                },
                userId);
        List<CvSummary> summaries = jdbc.query(
                "SELECT id, label, focus, text, verify, sort_order FROM cv_summary WHERE user_id = ? ORDER BY sort_order, id",
                (rs, n) -> new CvSummary(
                        rs.getLong("id"), rs.getString("label"), List.of((String[]) rs.getArray("focus").getArray()),
                        rs.getString("text"), rs.getBoolean("verify"), rs.getInt("sort_order")),
                userId);
        return jdbc.query(
                        """
                        SELECT p.title, c.name AS company, p.location, p.description
                        FROM posting p LEFT JOIN company c ON c.id = p.company_id WHERE p.id = ?
                        """,
                        (rs, n) -> new TailoringData(
                                userId, postingId, rs.getString("title"), rs.getString("company"),
                                rs.getString("location"), rs.getString("description"),
                                json.readValue(cvStatic.get(0), CvStatic.class), entries, bullets, summaries),
                        postingId)
                .stream()
                .findFirst();
    }

    /**
     * Deterministic fallback ranking: the user's confirmed bullets by cosine distance to the
     * posting. Bullets or postings without an embedding sort last, in ID order.
     */
    public List<Long> rankByEmbedding(long userId, long postingId) {
        return jdbc.queryForList(
                """
                SELECT b.id
                FROM bullet b JOIN posting p ON p.id = ?
                WHERE b.user_id = ? AND NOT ('verify' = ANY (b.tags))
                ORDER BY b.embedding <=> p.embedding NULLS LAST, b.id
                """,
                Long.class,
                postingId, userId);
    }

    public Optional<CvReady> findCvVersion(long userId, long postingId) {
        return jdbc.query(
                        "SELECT id, ats_score FROM cv_version WHERE user_id = ? AND posting_id = ? ORDER BY id DESC LIMIT 1",
                        (rs, n) -> new CvReady(userId, postingId, rs.getLong("id"), rs.getDouble("ats_score")),
                        userId, postingId)
                .stream()
                .findFirst();
    }

    public long insertCvVersion(
            long userId, long postingId, List<Long> bulletIds, String pdfKey, double atsScore, String summary, AtsReport report) {
        Array ids = jdbc.execute(
                (ConnectionCallback<Array>) connection -> connection.createArrayOf("bigint", bulletIds.toArray()));
        return jdbc.queryForObject(
                """
                INSERT INTO cv_version (user_id, posting_id, bullet_ids, pdf_s3_key, ats_score, summary, ats_report)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id
                """,
                Long.class,
                userId, postingId, ids, pdfKey, atsScore, summary, json.writeValueAsString(report));
    }
}
