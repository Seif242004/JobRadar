package com.jobradar.ingestion.internal.resolver;

import com.jobradar.ingestion.internal.source.FetchResponse;
import com.jobradar.ingestion.internal.source.HttpFetcher;
import com.jobradar.ingestion.internal.source.SourceFetchException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Careers resolver of docs/RESEARCH.md, "Company watchlist": checks each watchlist company's careers
 * page once, detects the ATS and stores {@code ats_type} and {@code board_token}. A page with no
 * known ATS is a custom site and is marked {@code email-alerts}.
 *
 * <p>Guardrails: one GET of the public careers page per company and check (plus robots.txt,
 * which is honoured), no login, no crawling beyond that page, re-checked at most every 30 days.
 */
@Component
public class CareersResolver {

    /** Careers sites change, so a resolved company is checked again after this long. */
    static final Duration RECHECK_AFTER = Duration.ofDays(30);

    /** Product token matched against robots.txt {@code User-agent} lines. */
    static final String ROBOTS_AGENT = "JobRadar";

    private static final Logger log = LoggerFactory.getLogger(CareersResolver.class);

    private final CompanyRepository companies;
    private final HttpFetcher http;
    private final Clock clock;

    CareersResolver(CompanyRepository companies, HttpFetcher http, Clock clock) {
        this.companies = companies;
        this.http = http;
        this.clock = clock;
    }

    /** Resolves every company that is due. Returns how many were resolved. */
    public int resolvePending() {
        Instant now = clock.instant();
        int resolved = 0;
        for (CompanyRepository.Pending company : companies.pending(now.minus(RECHECK_AFTER))) {
            Optional<AtsBoard> board = resolve(company.careersUrl());
            if (board.isPresent()) {
                companies.markResolved(company.id(), board.get(), now);
                resolved++;
                log.info("Resolved {}: {} {}", company.name(), board.get().atsType(), board.get().boardToken());
            }
            // Empty: the page could not be read. The row stays untouched and is retried next time.
        }
        return resolved;
    }

    /**
     * The ATS behind one careers URL; {@link AtsBoard#EMAIL_ALERTS} for a readable page with no
     * known ATS; empty when the page could not be read (error, non-2xx, or robots.txt forbids it).
     */
    Optional<AtsBoard> resolve(String careersUrl) {
        // A careers URL that already points at an ATS board needs no request at all.
        Optional<AtsBoard> fromUrl = AtsDetector.detect(careersUrl);
        if (fromUrl.isPresent()) {
            return fromUrl;
        }
        try {
            URI page = URI.create(careersUrl.strip());
            if (!robotsAllow(page)) {
                log.info("robots.txt of {} does not allow {}; left unresolved", page.getHost(), page.getPath());
                return Optional.empty();
            }
            FetchResponse response = http.get(page);
            if (!response.isSuccess()) {
                log.info("Careers page {} answered HTTP {}; left unresolved", careersUrl, response.status());
                return Optional.empty();
            }
            return Optional.of(AtsDetector.detect(response.body()).orElse(AtsBoard.EMAIL_ALERTS));
        } catch (SourceFetchException | IllegalArgumentException e) {
            log.info("Careers page {} could not be read ({}); left unresolved", careersUrl, e.getMessage());
            return Optional.empty();
        }
    }

    /** No robots.txt (or an unreadable one) means no restriction, as RFC 9309 says for 4xx. */
    private boolean robotsAllow(URI page) {
        FetchResponse robots;
        try {
            robots = http.get(page.resolve("/robots.txt"));
        } catch (SourceFetchException e) {
            return true;
        }
        String path = page.getRawPath() == null || page.getRawPath().isEmpty() ? "/" : page.getRawPath();
        return !robots.isSuccess() || RobotsRules.allows(robots.body(), ROBOTS_AGENT, path);
    }
}
