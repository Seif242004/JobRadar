package com.jobradar.ingestion.internal.resolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seeds the 30 watchlist companies of docs/PLAN.md into the shared {@code company} table from
 * {@code watchlist/companies.json} ({@code name}, {@code careersUrl}). Idempotent.
 *
 * <p>The file only says where each careers page is. Which ATS a company uses is never written
 * by hand: {@link CareersResolver} detects it from the page.
 */
@Component
public class WatchlistSeeder {

    static final String SEED_FILE = "watchlist/companies.json";

    private final CompanyRepository companies;
    private final JsonMapper json;

    WatchlistSeeder(CompanyRepository companies, JsonMapper json) {
        this.companies = companies;
        this.json = json;
    }

    /** Returns how many companies the seed file holds. */
    public int seed() {
        JsonNode entries;
        try (InputStream in = new ClassPathResource(SEED_FILE).getInputStream()) {
            entries = json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + SEED_FILE, e);
        }
        for (JsonNode entry : entries) {
            companies.seed(entry.path("name").asString(), entry.path("careersUrl").asString());
        }
        return entries.size();
    }
}
