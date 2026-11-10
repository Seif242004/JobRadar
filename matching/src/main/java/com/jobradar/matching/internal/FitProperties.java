package com.jobradar.matching.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code jobradar.matching.fit.*}: how similarity becomes a 0 to 100 fit score.
 *
 * @param similarityFloor   mean cosine similarity mapped to fit 0
 * @param similarityCeiling mean cosine similarity mapped to fit 100
 * @param topBullets        how many of the user's closest bullets are averaged
 * @param rerankMinFit      only postings with at least this embedding fit are sent to the LLM,
 *                          which keeps the LLM to "the top few postings a day"
 */
@ConfigurationProperties("jobradar.matching.fit")
public record FitProperties(
        @DefaultValue("0.30") double similarityFloor,
        @DefaultValue("0.70") double similarityCeiling,
        @DefaultValue("5") int topBullets,
        @DefaultValue("55") double rerankMinFit) {}
