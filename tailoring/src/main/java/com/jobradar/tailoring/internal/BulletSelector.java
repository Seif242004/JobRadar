package com.jobradar.tailoring.internal;

import com.jobradar.llm.LlmClient;
import com.jobradar.llm.LlmUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks the LLM which bullets to put on the CV, most relevant first. The model returns IDs only;
 * it is never asked to write or rephrase CV text.
 */
@Component
public class BulletSelector {

    private static final Logger log = LoggerFactory.getLogger(BulletSelector.class);

    static final int MAX_POSTING_CHARS = 3000;

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private static final String SYSTEM =
            """
            You select CV bullets for a job posting. You are given the posting and the candidate's \
            bullets, each with an ID in square brackets. Choose the 12 to 18 bullets that best match \
            the posting, most relevant first. Include the skill rows that fit the role. Use only \
            IDs from the list. Do not write, rephrase or add any text. Reply with one JSON object \
            and nothing else: {"bullet_ids": [<id>, <id>, ...]}""";

    private final LlmClient llm;
    private final JsonMapper json;

    BulletSelector(LlmClient llm, JsonMapper json) {
        this.llm = llm;
        this.json = json;
    }

    /** Empty when the LLM is unavailable or its reply cannot be read; the caller falls back. */
    public Optional<List<Long>> select(TailoringData data) {
        try {
            return parse(llm.complete(SYSTEM, prompt(data)));
        } catch (LlmUnavailableException e) {
            log.info("LLM unavailable for bullet selection: {}", e.getMessage());
            return Optional.empty();
        }
    }

    static String prompt(TailoringData data) {
        String description = data.postingDescription() == null ? "" : data.postingDescription();
        if (description.length() > MAX_POSTING_CHARS) {
            description = description.substring(0, MAX_POSTING_CHARS);
        }
        return "POSTING\n" + data.postingTitle()
                + (data.postingCompany() == null ? "" : " at " + data.postingCompany()) + "\n" + description
                + "\n\nBULLETS\n"
                + data.selectableBullets().stream()
                        .map(bullet -> "[" + bullet.id() + "] (" + bullet.section() + ") " + bullet.text())
                        .collect(Collectors.joining("\n"));
    }

    /** Reads {@code {"bullet_ids": [...]}}, tolerating code fences or prose around the object. */
    Optional<List<Long>> parse(String reply) {
        Matcher matcher = JSON_OBJECT.matcher(reply == null ? "" : reply);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            JsonNode ids = json.readTree(matcher.group()).path("bullet_ids");
            if (!ids.isArray()) {
                return Optional.empty();
            }
            List<Long> result = new ArrayList<>();
            for (JsonNode id : ids) {
                // Anything that is not an integer makes the whole reply unusable.
                if (!id.isIntegralNumber()) {
                    return Optional.empty();
                }
                result.add(id.asLong());
            }
            return Optional.of(result);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
