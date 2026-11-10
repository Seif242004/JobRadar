package com.jobradar.matching.internal;

/**
 * A fit score with its explanation.
 *
 * @param score  0 to 100
 * @param reason one line; from the LLM when it reranked, otherwise a note on the embedding match
 */
public record Fit(double score, String reason) {}
