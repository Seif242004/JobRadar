package com.jobradar.ingestion.internal.resolver;

import com.jobradar.ingestion.internal.source.Board;
import com.jobradar.ingestion.internal.source.BoardDirectory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL for the watchlist side of the shared {@code company} table. */
@Repository
public class CompanyRepository implements BoardDirectory {

    /** A company whose careers page still has to be checked. */
    public record Pending(long id, String name, String careersUrl) {}

    private final JdbcTemplate jdbc;

    CompanyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Adds a watchlist company. Idempotent: an existing row keeps everything it has and only
     * gains the careers URL when it had none (the row may have been created by a posting first).
     */
    public void seed(String name, String careersUrl) {
        jdbc.update(
                """
                INSERT INTO company (name, careers_url) VALUES (?, ?)
                ON CONFLICT (name) DO UPDATE SET careers_url = COALESCE(company.careers_url, EXCLUDED.careers_url)
                """,
                name, careersUrl);
    }

    /** Companies with a careers URL that were never resolved, or last resolved before {@code staleBefore}. */
    public List<Pending> pending(Instant staleBefore) {
        return jdbc.query(
                """
                SELECT id, name, careers_url FROM company
                WHERE careers_url IS NOT NULL AND (last_resolved_at IS NULL OR last_resolved_at < ?)
                ORDER BY id
                """,
                (row, i) -> new Pending(row.getLong("id"), row.getString("name"), row.getString("careers_url")),
                Timestamp.from(staleBefore));
    }

    public void markResolved(long companyId, AtsBoard board, Instant resolvedAt) {
        jdbc.update(
                "UPDATE company SET ats_type = ?, board_token = ?, last_resolved_at = ? WHERE id = ?",
                board.atsType(), board.boardToken(), Timestamp.from(resolvedAt), companyId);
    }

    @Override
    public List<Board> boards(String atsType) {
        return jdbc.query(
                "SELECT name, board_token FROM company WHERE ats_type = ? AND board_token IS NOT NULL ORDER BY id",
                (row, i) -> new Board(row.getString("name"), row.getString("board_token")),
                atsType);
    }
}
