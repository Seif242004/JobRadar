package com.jobradar.llm;

/** One chat completion. Implementations enforce the budget cap and hide the provider. */
public interface LlmClient {

    /**
     * @param system instructions for the model
     * @param user   the input to work on
     * @return the model's reply text
     * @throws LlmUnavailableException when no key is configured, the daily cap is reached, or the
     *                                 provider fails; callers must degrade, never fail the pipeline
     */
    String complete(String system, String user);
}
