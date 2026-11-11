package com.jobradar.matching.internal;

import com.jobradar.llm.EmbeddingClient;
import com.jobradar.llm.LlmClient;
import com.jobradar.llm.LlmUnavailableException;
import com.jobradar.matching.internal.EmbeddingRepository.NearBullet;
import com.jobradar.matching.internal.EmbeddingRepository.PendingBullet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Flow 2 after the hard filters: embedding similarity against the user's bullets, then an LLM
 * rerank of the postings that scored well, with a one-line reason.
 *
 * <p>Cost control: embeddings filter first, so the LLM sees only the top few postings. Every
 * provider failure degrades instead of failing: no LLM means the embedding fit is kept, and no
 * embeddings means no fit score at all (the posting is then ranked by age alone).
 */
@Component
@EnableConfigurationProperties(FitProperties.class)
public class FitScorer {

    private static final Logger log = LoggerFactory.getLogger(FitScorer.class);

    /** Posting text sent to the models is cut here; the top of a posting carries the signal. */
    static final int MAX_POSTING_CHARS = 3000;

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private static final String RERANK_SYSTEM =
            """
            You score how well a candidate fits a job posting. You are given the posting and the \
            candidate's most relevant CV bullets. Judge only from these bullets; do not assume \
            skills that are not written there. Reply with one JSON object and nothing else: \
            {"fit": <integer 0-100>, "reason": "<one sentence, at most 20 words>"}""";

    private final EmbeddingClient embeddings;
    private final LlmClient llm;
    private final EmbeddingRepository repository;
    private final FitProperties properties;
    private final JsonMapper json;

    FitScorer(
            EmbeddingClient embeddings,
            LlmClient llm,
            EmbeddingRepository repository,
            FitProperties properties,
            JsonMapper json) {
        this.embeddings = embeddings;
        this.llm = llm;
        this.repository = repository;
        this.properties = properties;
        this.json = json;
    }

    /** Empty when embeddings are unavailable or the user has no embedded bullets. */
    public Optional<Fit> score(PostingView posting, long userId) {
        List<NearBullet> nearest;
        try {
            ensurePostingEmbedding(posting);
            ensureBulletEmbeddings(userId);
            nearest = repository.nearestBullets(userId, posting.id(), properties.topBullets());
        } catch (LlmUnavailableException e) {
            log.info("Embeddings unavailable, posting {} gets no fit score: {}", posting.id(), e.getMessage());
            return Optional.empty();
        }
        OptionalDouble mean = EmbeddingRepository.meanSimilarity(nearest);
        if (mean.isEmpty()) {
            return Optional.empty();
        }
        double embeddingFit = toFit(mean.getAsDouble());
        Fit fallback = new Fit(embeddingFit, "Embedding match with " + nearest.size() + " profile bullets");

        // Only the postings that already look relevant are worth an LLM call.
        if (embeddingFit < properties.rerankMinFit()) {
            return Optional.of(fallback);
        }
        return Optional.of(rerank(posting, nearest).orElse(fallback));
    }

    /** Linear map of [floor, ceiling] onto [0, 100], clamped. */
    double toFit(double similarity) {
        double span = properties.similarityCeiling() - properties.similarityFloor();
        double fit = (similarity - properties.similarityFloor()) / span * 100.0;
        return Math.max(0.0, Math.min(100.0, fit));
    }

    private void ensurePostingEmbedding(PostingView posting) {
        if (!repository.postingHasEmbedding(posting.id())) {
            repository.savePostingEmbedding(posting.id(), embedAll(List.of(postingText(posting))).get(0));
        }
    }

    /** Embeds bullets added since the last run; a no-op once the profile is embedded. */
    private void ensureBulletEmbeddings(long userId) {
        List<PendingBullet> pending = repository.bulletsWithoutEmbedding(userId);
        if (pending.isEmpty()) {
            return;
        }
        List<float[]> vectors = embedAll(pending.stream().map(PendingBullet::text).toList());
        for (int i = 0; i < pending.size(); i++) {
            repository.saveBulletEmbedding(pending.get(i).id(), vectors.get(i));
        }
    }

    private List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = embeddings.embed(texts);
        if (vectors == null || vectors.size() != texts.size()) {
            throw new LlmUnavailableException("Embedding client returned no usable vectors");
        }
        return vectors;
    }

    private Optional<Fit> rerank(PostingView posting, List<NearBullet> nearest) {
        String user = "POSTING\n" + postingText(posting) + "\n\nCANDIDATE BULLETS\n"
                + nearest.stream().map(bullet -> "- " + bullet.text()).collect(Collectors.joining("\n"));
        try {
            String reply = llm.complete(RERANK_SYSTEM, user);
            // Models sometimes wrap the object in code fences or prose: take the first {...}.
            Matcher matcher = JSON_OBJECT.matcher(reply == null ? "" : reply);
            if (!matcher.find()) {
                return Optional.empty();
            }
            JsonNode node = json.readTree(matcher.group());
            if (!node.path("fit").isNumber()) {
                return Optional.empty();
            }
            double fit = Math.max(0.0, Math.min(100.0, node.path("fit").asDouble()));
            String reason = node.path("reason").asString("").strip();
            return Optional.of(new Fit(fit, reason.isEmpty() ? "Reranked by the LLM" : reason));
        } catch (LlmUnavailableException e) {
            log.info("LLM rerank skipped for posting {}: {}", posting.id(), e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            // Malformed JSON from the model: keep the embedding fit.
            log.info("LLM rerank reply for posting {} was not usable", posting.id());
            return Optional.empty();
        }
    }

    static String postingText(PostingView posting) {
        String text = posting.title()
                + (posting.location() == null ? "" : "\n" + posting.location())
                + (posting.description() == null ? "" : "\n" + posting.description());
        return text.length() <= MAX_POSTING_CHARS ? text : text.substring(0, MAX_POSTING_CHARS);
    }
}
