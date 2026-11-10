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
 * @param dailyCallCap   budget cap: chat completions allowed per UTC day in this process
 */
@ConfigurationProperties("jobradar.ai")
public record AiProperties(
        @DefaultValue("https://integrate.api.nvidia.com/v1") String baseUrl,
        @DefaultValue("") String key,
        @DefaultValue("openai/gpt-oss-20b") String model,
        @DefaultValue({}) List<String> fallbackModels,
        @DefaultValue("baai/bge-m3") String embeddingModel,
        @DefaultValue("200") int dailyCallCap,
        @DefaultValue("30s") Duration timeout) {}
