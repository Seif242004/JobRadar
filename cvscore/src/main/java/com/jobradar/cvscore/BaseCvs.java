package com.jobradar.cvscore;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The user's own finished CVs. The PDF is stored unchanged under its original file name; only
 * its extracted text is used, to score postings. Nothing here edits or rewrites a CV.
 */
@Service
public class BaseCvs {

    /** A stored CV without its bytes. */
    public record Info(long id, String label, String filename) {}

    /** How one CV covers one posting: keyword coverage in percent and the keywords it lacks. */
    public record Score(long cvId, String label, double ats, int keywords, List<String> missing) {}

    private final JdbcTemplate jdbc;

    BaseCvs(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores or replaces (same file name) a CV. Rejects anything that is not a readable PDF with text. */
    public Info save(long userId, String label, String filename, byte[] pdf) {
        String text = extractText(pdf);
        if (text.isBlank()) {
            throw new IllegalArgumentException("The PDF has no selectable text, so it cannot be scored.");
        }
        long id = jdbc.queryForObject(
                """
                INSERT INTO base_cv (user_id, label, filename, pdf, text) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id, filename) DO UPDATE SET label = EXCLUDED.label, pdf = EXCLUDED.pdf, text = EXCLUDED.text
                RETURNING id
                """,
                Long.class, userId, label, filename, pdf, text);
        return new Info(id, label, filename);
    }

    public List<Info> list(long userId) {
        return jdbc.query(
                "SELECT id, label, filename FROM base_cv WHERE user_id = ? ORDER BY id",
                (rs, n) -> new Info(rs.getLong("id"), rs.getString("label"), rs.getString("filename")),
                userId);
    }

    /** The stored file exactly as uploaded, with its name. */
    public Optional<byte[]> pdf(long userId, long cvId) {
        return jdbc.query("SELECT pdf FROM base_cv WHERE user_id = ? AND id = ?", (rs, n) -> rs.getBytes("pdf"), userId, cvId)
                .stream().findFirst();
    }

    public Optional<Info> info(long userId, long cvId) {
        return list(userId).stream().filter(i -> i.id() == cvId).findFirst();
    }

    public boolean delete(long userId, long cvId) {
        return jdbc.update("DELETE FROM base_cv WHERE user_id = ? AND id = ?", userId, cvId) == 1;
    }

    /** Scores the posting against every stored CV, best first. */
    public List<Score> score(long userId, String title, String description) {
        int keywords = AtsScorer.keywordCount(title, description);
        return jdbc.query(
                        "SELECT id, label, text FROM base_cv WHERE user_id = ? ORDER BY id",
                        (rs, n) -> {
                            String text = rs.getString("text");
                            List<String> missing = AtsScorer.missing(title, description, text);
                            return new Score(
                                    rs.getLong("id"), rs.getString("label"),
                                    AtsScorer.score(title, description, text), keywords,
                                    missing.size() > 12 ? missing.subList(0, 12) : missing);
                        },
                        userId)
                .stream()
                .sorted(Comparator.comparingDouble(Score::ats).reversed())
                .toList();
    }

    static String extractText(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new IllegalArgumentException("Not a readable PDF.", e);
        }
    }
}
