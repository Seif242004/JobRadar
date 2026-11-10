/**
 * Provider-neutral LLM and embedding clients.
 *
 * <p>JobRadar has exactly two LLM steps, rerank (matching, in the api) and tailor (tailoring
 * worker). Both live in different deployables, so the client sits in this shared library. Callers
 * depend on {@link com.jobradar.llm.LlmClient} and {@link com.jobradar.llm.EmbeddingClient} only,
 * which keeps the provider swappable (SPEC.md D1, D3).
 */
@org.springframework.modulith.ApplicationModule(displayName = "llm")
package com.jobradar.llm;
