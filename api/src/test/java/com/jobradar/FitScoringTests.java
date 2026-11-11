package com.jobradar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobradar.llm.EmbeddingClient;
import com.jobradar.llm.LlmUnavailableException;
import com.jobradar.matching.internal.PostingMatcher;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Embedding fit, LLM rerank and their fallbacks, with real pgvector similarity. The embedding
 * client is a stub that maps a topic word to an axis, so cosine similarities are known exactly.
 */
class FitScoringTests extends PostgresIntegrationTest {

    @Autowired
    PostingMatcher matcher;

    @Autowired
    JdbcTemplate jdbc;

    long userId;

    @BeforeEach
    void seed() {
        reset(telegram, embeddingClient, llmClient);
        jdbc.update("TRUNCATE app_user, posting, company CASCADE");
        userId = jdbc.queryForObject(
                "INSERT INTO app_user (email, telegram_chat_id, daily_apply_cap) VALUES ('fit@example.com', 77, 5) RETURNING id",
                Long.class);
        jdbc.update(
                """
                INSERT INTO profile (user_id, roles, cities, remote, job_types, graduation_date)
                VALUES (?, '{Backend Engineer}', '{Cairo}', TRUE, '{internship,full-time}', DATE '2027-06-30')
                """,
                userId);
        jdbc.update("INSERT INTO bullet (user_id, section, text) VALUES (?, 'experience', 'Built backend services')", userId);
        jdbc.update("INSERT INTO bullet (user_id, section, text) VALUES (?, 'project', 'Built a backend API')", userId);
        // Stub: every text is embedded by its topic word.
        when(embeddingClient.embed(anyList())).thenAnswer(call -> {
            List<String> texts = call.getArgument(0);
            return texts.stream().map(FitScoringTests::topicVector).toList();
        });
    }

    /**
     * "backend" texts lie on axis 0; "partly" texts at 60 degrees to it (cosine 0.5); anything
     * else on axis 1 (cosine 0).
     */
    private static float[] topicVector(String text) {
        float[] vector = new float[EmbeddingClient.DIMENSIONS];
        String lower = text.toLowerCase();
        if (lower.contains("partly")) {
            vector[0] = 0.5f;
            vector[1] = (float) Math.sqrt(0.75);
        } else if (lower.contains("backend")) {
            vector[0] = 1f;
        } else {
            vector[1] = 1f;
        }
        return vector;
    }

    private long posting(String title, Duration age) {
        return jdbc.queryForObject(
                """
                INSERT INTO posting (source, source_id, title, location, description, apply_channel, apply_target,
                                     first_seen_at, content_hash)
                VALUES ('fixture', ?, ?, 'Cairo, Egypt', 'Description', 'ats_form', 'https://example.com/j', ?, ?)
                RETURNING id
                """,
                Long.class,
                title, title, Timestamp.from(Instant.now().minus(age)), title);
    }

    private Map<String, Object> match(long postingId) {
        return jdbc.queryForMap("SELECT fit_score, priority, band, reasons FROM match WHERE posting_id = ?", postingId);
    }

    @Test
    void aCloseMatchIsRerankedByTheLlmAndItsScoreAndReasonAreStored() {
        when(llmClient.complete(anyString(), anyString()))
                .thenReturn("```json\n{\"fit\": 91, \"reason\": \"Strong backend overlap.\"}\n```");
        long id = posting("Backend Engineer", Duration.ofMinutes(5));

        matcher.match(id);

        Map<String, Object> row = match(id);
        assertThat(((Number) row.get("fit_score")).doubleValue()).isEqualTo(91.0);
        assertThat(row.get("band")).isEqualTo("hot");
        assertThat(row.get("reasons")).isEqualTo("In Egypt, full-time; Strong backend overlap.");
        // Priority = fit x 0.5^(age / 6 h); at 5 minutes old that is just under the fit.
        assertThat(((Number) row.get("priority")).doubleValue()).isCloseTo(91.0 * Math.pow(0.5, 5.0 / 360), within(0.5));
        // The LLM saw the posting and the user's own bullets, nothing invented.
        verify(llmClient).complete(anyString(), contains("Built backend services"));
        // With a fit score the Apply-now push waits for the tailored CV (see ApplyNowTests); here a
        // hot-queue request for that CV goes out instead.
        verify(telegram, never()).sendMessage(anyLong(), any(), any(), any());
        verify(tailorRequests).publish(userId, id, true);
    }

    @Test
    void aWeakMatchIsNotSentToTheLlmAndIsNotPushedAsHot() {
        long id = posting("Marketing Specialist", Duration.ofMinutes(5));

        matcher.match(id);

        Map<String, Object> row = match(id);
        // Cosine 0 is below the 0.30 floor, so the fit is 0.
        assertThat(((Number) row.get("fit_score")).doubleValue()).isZero();
        // Fresh, but fit under 80: warm, so no instant push.
        assertThat(row.get("band")).isEqualTo("warm");
        verify(llmClient, never()).complete(anyString(), anyString());
        verify(telegram, never()).sendMessage(anyLong(), any(), any(), any());
        // A warm match with fit 0 is not worth a CV: no tailoring request.
        verify(tailorRequests, never()).publish(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void similarityIsMappedLinearlyBetweenFloorAndCeiling() {
        // Cosine 0.5 with floor 0.30 and ceiling 0.70 is fit 50, under the rerank threshold of 55.
        long id = posting("Partly related role", Duration.ofHours(3));

        matcher.match(id);

        assertThat(((Number) match(id).get("fit_score")).doubleValue()).isCloseTo(50.0, within(0.01));
        verify(llmClient, never()).complete(anyString(), anyString());
    }

    @Test
    void whenTheLlmIsUnavailableTheEmbeddingFitIsKept() {
        when(llmClient.complete(anyString(), anyString())).thenThrow(new LlmUnavailableException("Daily cap reached"));
        long id = posting("Backend Engineer", Duration.ofMinutes(5));

        matcher.match(id);

        Map<String, Object> row = match(id);
        // Cosine 1.0 is above the ceiling: fit 100 from embeddings alone.
        assertThat(((Number) row.get("fit_score")).doubleValue()).isEqualTo(100.0);
        assertThat((String) row.get("reasons")).contains("Embedding match with 2 profile bullets");
    }

    @Test
    void anUnusableLlmReplyKeepsTheEmbeddingFit() {
        when(llmClient.complete(anyString(), anyString())).thenReturn("I think this is a great fit!");
        long id = posting("Backend Engineer", Duration.ofMinutes(5));

        matcher.match(id);

        assertThat(((Number) match(id).get("fit_score")).doubleValue()).isEqualTo(100.0);
    }

    @Test
    void whenEmbeddingsAreUnavailableThereIsNoFitAndAgeAloneDecidesTheBand() {
        reset(embeddingClient);
        when(embeddingClient.embed(anyList())).thenThrow(new LlmUnavailableException("No API key configured"));
        long id = posting("Backend Engineer", Duration.ofMinutes(5));

        matcher.match(id);

        Map<String, Object> row = match(id);
        assertThat(row.get("fit_score")).isNull();
        assertThat(row.get("priority")).isNull();
        assertThat(row.get("band")).isEqualTo("hot");
    }

    @Test
    void embeddingsAreComputedOnceAndIneligiblePostingsCostNothing() {
        long eligible = posting("Backend Engineer", Duration.ofHours(30));
        matcher.match(eligible);
        // A redelivery must reuse the stored posting and bullet embeddings.
        matcher.match(eligible);
        // One call for the posting, one for the two bullets.
        verify(embeddingClient, times(2)).embed(anyList());

        reset(embeddingClient);
        matcher.match(posting("Senior Backend Engineer", Duration.ofMinutes(5)));
        verify(embeddingClient, never()).embed(anyList());
    }

    @Test
    void unconfirmedBulletsDoNotRaiseTheFit() {
        jdbc.update("UPDATE bullet SET tags = '{verify}' WHERE user_id = ?", userId);
        long id = posting("Backend Engineer", Duration.ofMinutes(5));

        matcher.match(id);

        assertThat(match(id).get("fit_score")).isNull();
    }
}
