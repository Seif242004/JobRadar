package com.jobradar.matching.internal;

import java.util.List;
import java.util.OptionalDouble;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** pgvector reads and writes for posting and bullet embeddings. */
@Repository
public class EmbeddingRepository {

    /** A bullet that still needs an embedding. */
    public record PendingBullet(long id, String text) {}

    /** One of the user's bullets closest to a posting. */
    public record NearBullet(long id, String text, double similarity) {}

    private final JdbcTemplate jdbc;

    EmbeddingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean postingHasEmbedding(long postingId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT embedding IS NOT NULL FROM posting WHERE id = ?", Boolean.class, postingId));
    }

    public void savePostingEmbedding(long postingId, float[] vector) {
        jdbc.update("UPDATE posting SET embedding = ?::vector WHERE id = ?", literal(vector), postingId);
    }

    public List<PendingBullet> bulletsWithoutEmbedding(long userId) {
        return jdbc.query(
                "SELECT id, text FROM bullet WHERE user_id = ? AND embedding IS NULL ORDER BY id",
                (rs, rowNum) -> new PendingBullet(rs.getLong("id"), rs.getString("text")),
                userId);
    }

    public void saveBulletEmbedding(long bulletId, float[] vector) {
        jdbc.update("UPDATE bullet SET embedding = ?::vector WHERE id = ?", literal(vector), bulletId);
    }

    /**
     * The user's {@code limit} bullets nearest to the posting by cosine distance ({@code <=>}).
     * Bullets tagged {@code verify} are skipped: unconfirmed claims must not raise the fit.
     */
    public List<NearBullet> nearestBullets(long userId, long postingId, int limit) {
        return jdbc.query(
                """
                SELECT b.id, b.text, 1 - (b.embedding <=> p.embedding) AS similarity
                FROM bullet b JOIN posting p ON p.id = ?
                WHERE b.user_id = ? AND b.embedding IS NOT NULL AND p.embedding IS NOT NULL
                  AND NOT ('verify' = ANY (b.tags))
                ORDER BY b.embedding <=> p.embedding
                LIMIT ?
                """,
                (rs, rowNum) -> new NearBullet(rs.getLong("id"), rs.getString("text"), rs.getDouble("similarity")),
                postingId, userId, limit);
    }

    /** Mean similarity of the nearest bullets, empty when either side has no embeddings. */
    public static OptionalDouble meanSimilarity(List<NearBullet> bullets) {
        return bullets.stream().mapToDouble(NearBullet::similarity).average();
    }

    /** pgvector text form: {@code [0.1,0.2,...]}. */
    static String literal(float[] vector) {
        StringBuilder text = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(vector[i]);
        }
        return text.append(']').toString();
    }
}
