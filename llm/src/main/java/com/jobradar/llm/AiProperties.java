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
        @DefaultValue("nvidia/nemotron-3.5-lightning-30b-a3b") String model,
        @DefaultValue({}) List<String> fallbackModels,
        // baai/bge-m3 is not served by NVIDIA Build. nvidia/llama-3.2-nv-embedqa-1b-v1 is listed but answers
        // 404 (checked 2026-11-11). nemotron-3-embed-1b works, returns 2048 values and rejects "dimensions",
        // so the first 1024 are kept and renormalised (see embeddingTruncate).
        @DefaultValue("nvidia/nemotron-3-embed-1b") String embeddingModel,
        @DefaultValue("0") int embeddingDimensions,
        // NVIDIA retrieval models require input_type; blank omits the field for other providers.
        @DefaultValue("passage") String embeddingInputType,
        // For models that cannot be asked for a smaller size and return more than 1024 values (Matryoshka
        // style models): keep the first 1024 and renormalise. Off by default; 0 dimensions omits the field.
        @DefaultValue("true") boolean embeddingTruncate,
        // Reasoning models spend the whole token budget thinking and return no text; this asks NVIDIA-hosted
        // models for a direct answer. Off by default because other providers may reject the field.
        @DefaultValue("false") boolean chatDisableThinking,
        @DefaultValue("200") int dailyCallCap,
        @DefaultValue("30s") Duration timeout) {}
