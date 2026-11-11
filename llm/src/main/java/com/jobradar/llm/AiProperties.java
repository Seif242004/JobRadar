package com.jobradar.llm;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code jobradar.ai.*}: an OpenAI-compatible endpoint (SPEC.md D1 to D3).
 *
 * @param key            API key; empty disables every call
 * @param model          chat model tried first
 * @param fallbackModels tried in order when the provider no longer serves {@code model}
 * @param embeddingDimensions requested output size; must equal {@link EmbeddingClient#DIMENSIONS}
 * @param dailyCallCap   budget cap: chat completions allowed per UTC day in this process
 */
@ConfigurationProperties("jobradar.ai")
public record AiProperties(
        @DefaultValue("https://integrate.api.nvidia.com/v1") String baseUrl,
        @DefaultValue("") String key,
        @DefaultValue("openai/gpt-oss-20b") String model,
        @DefaultValue({}) List<String> fallbackModels,
        // baai/bge-m3 is not served by NVIDIA Build (checked against GET /v1/models). This model is
        // multilingual and supports reduced output sizes, so it keeps the schema at vector(1024).
        @DefaultValue("nvidia/llama-3.2-nv-embedqa-1b-v1") String embeddingModel,
        @DefaultValue("1024") int embeddingDimensions,
        // NVIDIA retrieval models require input_type; blank omits the field for other providers.
        @DefaultValue("passage") String embeddingInputType,
        @DefaultValue("200") int dailyCallCap,
        @DefaultValue("30s") Duration timeout) {}
