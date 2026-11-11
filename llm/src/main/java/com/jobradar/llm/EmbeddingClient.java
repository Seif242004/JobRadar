package com.jobradar.llm;

import java.util.List;

/** Text embeddings; one vector per input, in input order. */
public interface EmbeddingClient {

    /** Vector size of the configured model; must equal the {@code vector(N)} columns (1024). */
    int DIMENSIONS = 1024;

    /**
     * @throws LlmUnavailableException when no key is configured or the provider fails
     */
    List<float[]> embed(List<String> texts);
}
